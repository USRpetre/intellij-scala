package org.jetbrains.jps.incremental.scala

import _root_.java.io.File
import java.net.InetAddress
import _root_.java.util
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.PathUtil
import com.intellij.util.Processor
import org.jetbrains.jps.ModuleChunk
import org.jetbrains.jps.builders.{FileProcessor, BuildRootDescriptor, BuildTarget, DirtyFilesHolder}
import org.jetbrains.jps.builders.java.{JavaBuilderUtil, JavaSourceRootDescriptor}
import org.jetbrains.jps.builders.impl.TargetOutputIndexImpl
import org.jetbrains.jps.incremental._
import org.jetbrains.jps.incremental.messages.ProgressMessage
import org.jetbrains.jps.incremental.scala.data.CompilationData
import org.jetbrains.jps.incremental.scala.data.CompilerData
import org.jetbrains.jps.incremental.scala.data.SbtData
import collection.JavaConverters._
import org.jetbrains.jps.incremental.ModuleLevelBuilder.{OutputConsumer, ExitCode}
import org.jetbrains.jps.incremental.scala.local.{IdeClient, IdeClientScalac, IdeClientSbt, LocalServer}
import remote.RemoteServer
import com.intellij.openapi.diagnostic.{Logger => JpsLogger}
import org.jetbrains.jps.incremental.scala.model.{Order, CompilerType}
import org.jetbrains.jps.builders.java.dependencyView.Mappings
import org.jetbrains.jps.model.module.JpsModule
import _root_.scala.collection.mutable

/**
 * @author Pavel Fatin
 */
class ScalaBuilder(builderCategory: BuilderCategory) extends ModuleLevelBuilder(builderCategory) {
  def getPresentableName = "Scala builder"

  def build(context: CompileContext, chunk: ModuleChunk,
            dirtyFilesHolder: DirtyFilesHolder[JavaSourceRootDescriptor, ModuleBuildTarget],
            outputConsumer: OutputConsumer): ModuleLevelBuilder.ExitCode = {

    val successfullyCompiled = mutable.Set[File]()
    var delta: Mappings = null

    if (ChunkExclusionService.isExcluded(chunk)) return ExitCode.NOTHING_DONE

    context.processMessage(new ProgressMessage("Searching for compilable files..."))
    val filesToCompile = ScalaBuilder.collectCompilableFiles(context, chunk, dirtyFilesHolder)

    if (filesToCompile.isEmpty) return ExitCode.NOTHING_DONE

    val sources = filesToCompile.keySet.toSeq

    val modules = chunk.getModules.asScala.toSet
    var client: IdeClient = null

    context.processMessage(new ProgressMessage("Reading compilation settings..."))
    val settings = SettingsManager.getGlobalSettings(context.getProjectDescriptor.getModel.getGlobal)
    settings.getCompilerType match {
      case CompilerType.SBT =>
        if (!hasDirtyDependenciesOrFiles(chunk, context, dirtyFilesHolder)) return ExitCode.NOTHING_DONE
        // Delete dirty class files (to handle force builds and form changes)
        BuildOperations.cleanOutputsCorrespondingToChangedFiles(context, dirtyFilesHolder)

        client = new IdeClientSbt("sbt", context, modules.map(_.getName).toSeq, outputConsumer, filesToCompile.get)
      case CompilerType.SCALAC =>
        delta = context.getProjectDescriptor.dataManager.getMappings.createDelta()
        val callback = delta.getCallback
        client = new IdeClientScalac("scalac", context, modules.map(_.getName).toSeq, outputConsumer, callback, successfullyCompiled)
    }

    val compileResult = compile(context, chunk, sources, modules, client)

    compileResult match {
      case Left(error) =>
        client.error(error)
        ExitCode.ABORT
      case _ if client.hasReportedErrors || client.isCanceled => ExitCode.ABORT
      case Right(code) =>
        if (delta != null && JavaBuilderUtil.updateMappings(context, delta, dirtyFilesHolder, chunk, sources.asJava, successfullyCompiled.asJava))
          ExitCode.ADDITIONAL_PASS_REQUIRED
        else {
          client.progress("Compilation completed", Some(1.0F))
          code
        }
    }
  }

  //checks chanded files for sbt compiler
  //returns false if there is nothing to compile
  private def hasDirtyDependenciesOrFiles(chunk: ModuleChunk,
                                      context: CompileContext,
                                      dirtyFilesHolder: DirtyFilesHolder[JavaSourceRootDescriptor, ModuleBuildTarget]
                                             ): Boolean = {
    val representativeTarget = chunk.representativeTarget()

    val timestamps = new TargetTimestamps(context)

    val targetTimestamp = timestamps.get(representativeTarget)

    val hasDirtyDependencies = {
      val dependencies = ScalaBuilder.moduleDependenciesIn(context, representativeTarget)

      targetTimestamp.map {
        thisTimestamp =>
          dependencies.exists {
            dependency =>
              val thatTimestamp = timestamps.get(dependency)
              thatTimestamp.map(_ > thisTimestamp).getOrElse(true)
          }
      } getOrElse {
        dependencies.nonEmpty
      }
    }

    if (!hasDirtyDependencies &&
            !ScalaBuilder.hasDirtyFiles(dirtyFilesHolder) &&
            !dirtyFilesHolder.hasRemovedFiles) {

      if (targetTimestamp.isEmpty) {
        timestamps.set(representativeTarget, context.getCompilationStartStamp)
      }
      return false
    } else timestamps.set(representativeTarget, context.getCompilationStartStamp)
    true
  }

  private def compile(context: CompileContext,
                      chunk: ModuleChunk,
                      sources: Seq[File],
                      modules: Set[JpsModule],
                      client: Client): Either[String, ModuleLevelBuilder.ExitCode] = {
    for {
      sbtData <-  ScalaBuilder.sbtData
      compilerData <- CompilerData.from(context, chunk)
      compilationData <- CompilationData.from(sources, context, chunk)
    }
    yield {
      scalaLibraryWarning(modules, compilationData, client)

      val server = getServer(context)
      server.compile(sbtData, compilerData, compilationData, client)
    }
  }


  private def scalaLibraryWarning(modules: Set[JpsModule], compilationData: CompilationData, client: Client) {
    val hasScalaFacet = modules.exists(SettingsManager.getFacetSettings(_) != null)
    val hasScalaLibrary = compilationData.classpath.exists(_.getName.startsWith("scala-library"))

    if (hasScalaFacet && !hasScalaLibrary) {
      val names = modules.map(_.getName).mkString(", ")
      client.warning("No 'scala-library*.jar' in module dependencies [%s]".format(names))
    }
  }

  private def getServer(context: CompileContext): Server = {
    val settings = SettingsManager.getGlobalSettings(context.getProjectDescriptor.getModel.getGlobal)

    if (settings.isCompileServerEnabled && JavaBuilderUtil.CONSTANT_SEARCH_SERVICE.get(context) != null) {
      ScalaBuilder.cleanLocalServerCache()
      new RemoteServer(InetAddress.getByName(null), settings.getCompileServerPort)
    } else {
      ScalaBuilder.localServer
    }
  }

  override def getCompilableFileExtensions: util.List[String] = List("scala").asJava
}

object ScalaBuilder {
  val Log = JpsLogger.getInstance(classOf[ScalaBuilder])

  // Cached local localServer
  private var cachedServer: Option[Server] = None

  private val lock = new Object()

  private def localServer = {
    lock.synchronized {
      val server = cachedServer.getOrElse(new LocalServer())
      cachedServer = Some(server)
      server
    }
  }

  private def cleanLocalServerCache() {
    lock.synchronized {
      cachedServer = None
    }
  }

  private lazy val sbtData = {
    val classLoader = getClass.getClassLoader
    val pluginRoot = new File(PathUtil.getJarPathForClass(getClass)).getParentFile
    val systemRoot = Utils.getSystemRoot
    val javaClassVersion = System.getProperty("java.class.version")

    SbtData.from(classLoader, pluginRoot, systemRoot, javaClassVersion)
  }

  private def hasDirtyFiles(dirtyFilesHolder: DirtyFilesHolder[JavaSourceRootDescriptor, ModuleBuildTarget]): Boolean = {
    var result = false

    dirtyFilesHolder.processDirtyFiles(new FileProcessor[JavaSourceRootDescriptor, ModuleBuildTarget] {
      def apply(target: ModuleBuildTarget, file: File, root: JavaSourceRootDescriptor) = {
        result = true
        false
      }
    })

    result
  }

  private def collectCompilableFiles(context: CompileContext, chunk: ModuleChunk,
                                     dirtyFilesHolder: DirtyFilesHolder[JavaSourceRootDescriptor, ModuleBuildTarget]
                                            ): collection.Map[File, BuildTarget[_ <: BuildRootDescriptor]] = {

    val result = collection.mutable.HashMap[File, BuildTarget[_ <: BuildRootDescriptor]]()

    val project = context.getProjectDescriptor

    val rootIndex = project.getBuildRootIndex
    val excludeIndex = project.getModuleExcludeIndex

    val settings = SettingsManager.getGlobalSettings(context.getProjectDescriptor.getModel.getGlobal)
    val extensionsToCollect = settings.getCompileOrder match {
      case Order.Mixed => List(".scala", ".java")
      case _ => List(".scala")
    }

    def checkAndCollectFile(file: File, target: ModuleBuildTarget): Unit = {
      val fileName = file.getName
      if (extensionsToCollect.exists(fileName.endsWith) && !excludeIndex.isExcluded(file))
        result += file -> target
    }

    val scalacUsed = settings.getCompilerType == CompilerType.SCALAC
    val sbtUsed = settings.getCompilerType == CompilerType.SBT

    if (scalacUsed) {
      dirtyFilesHolder.processDirtyFiles(new FileProcessor[JavaSourceRootDescriptor, ModuleBuildTarget] {
        def apply(target: ModuleBuildTarget, file: File, root: JavaSourceRootDescriptor) = {
          checkAndCollectFile(file, target)
          true
        }
      })
    }
    else if (sbtUsed) {
      for {
        target <- chunk.getTargets.asScala
        root <- rootIndex.getTargetRoots(target, context).asScala
      } {
        FileUtil.processFilesRecursively(root.getRootFile, new Processor[File] {
          def process(file: File) = {
            checkAndCollectFile(file, target)
            true
          }
        })
      }
    }

    //if no scala files to compile, return empty map
    if (scalacUsed && !result.keySet.exists(_.getName.endsWith(".scala"))) Map.empty
    else result
  }

  private def moduleDependenciesIn(context: CompileContext, target: ModuleBuildTarget): Seq[ModuleBuildTarget] = {
    val dependencies = {
      val targetOutputIndex = {
        val targets = context.getProjectDescriptor.getBuildTargetIndex.getAllTargets
        new TargetOutputIndexImpl(targets, context)
      }
      target.computeDependencies(context.getProjectDescriptor.getBuildTargetIndex, targetOutputIndex).asScala
    }

    dependencies.filter(_.isInstanceOf[ModuleBuildTarget]).map(_.asInstanceOf[ModuleBuildTarget]).toSeq
  }
}