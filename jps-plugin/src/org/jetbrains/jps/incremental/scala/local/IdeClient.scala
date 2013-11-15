package org.jetbrains.jps.incremental.scala
package local

import org.jetbrains.jps.incremental.{BinaryContent, CompiledClass, Utils, CompileContext}
import org.jetbrains.jps.incremental.ModuleLevelBuilder.OutputConsumer
import org.jetbrains.jps.incremental.messages.BuildMessage.Kind
import java.io.{IOException, File}
import org.jetbrains.jps.incremental.messages.{BuildMessage, FileDeletedEvent, ProgressMessage, CompilerMessage}
import scala.util.control.Exception._
import com.intellij.openapi.util.Key
import java.util
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.jps.builders.{BuildRootDescriptor, BuildTarget}
import org.jetbrains.jps.builders.java.dependencyView.Callbacks
import scala.collection.mutable
import java.util.Collections
import org.jetbrains.asm4.ClassReader
import collection.JavaConverters._


/**
 * Nikolay.Tropin
 * 11/15/13
 */
abstract class IdeClient(compilerName: String,
                         context: CompileContext,
                         modules: Seq[String],
                         consumer: OutputConsumer) extends Client {

  private var hasErrors = false

  def message(kind: Kind, text: String, source: Option[File], line: Option[Long], column: Option[Long]) {
    if (kind == Kind.ERROR) {
      hasErrors = true
    }

    val name = if (source.isEmpty) compilerName else ""

    val sourcePath = source.map(file => file.getPath)

    context.processMessage(new CompilerMessage(name, kind, text, sourcePath.orNull,
      -1L, -1L, -1L, line.getOrElse(-1L), column.getOrElse(-1L)))
  }

  def trace(exception: Throwable) {
    context.processMessage(new CompilerMessage(compilerName, exception))
  }

  def progress(text: String, done: Option[Float]) {
    val formattedText = if (text.isEmpty) "" else {
      val decapitalizedText = text.charAt(0).toLower.toString + text.substring(1)
      "%s: %s [%s]".format(compilerName, decapitalizedText, modules.mkString(", "))
    }
    context.processMessage(new ProgressMessage(formattedText, done.getOrElse(-1.0F)))
  }

  def debug(text: String) {
    ScalaBuilder.Log.info(text)
  }

  def deleted(module: File) {
    val paths = util.Collections.singletonList(FileUtil.toCanonicalPath(module.getPath))
    context.processMessage(new FileDeletedEvent(paths))
  }

  def isCanceled = context.getCancelStatus.isCanceled

  def hasReportedErrors: Boolean = hasErrors
}

class IdeClientSbt(compilerName: String,
                   context: CompileContext,
                   modules: Seq[String],
                   consumer: OutputConsumer,
                   sourceToTarget: File => Option[BuildTarget[_ <: BuildRootDescriptor]])
        extends IdeClient(compilerName, context, modules, consumer) {

  def generated(source: File, outputFile: File, name: String) {
    invalidateBoundForms(source)
    val target = sourceToTarget(source).getOrElse {
      throw new RuntimeException("Unknown source file: " + source)
    }
    val compiledClass = new LazyCompiledClass(outputFile, source, name)
    consumer.registerCompiledClass(target, compiledClass)
  }

  def processSource(source: File): Unit = {}

  // TODO Expect JPS compiler in UI-designer to take generated class events into account
  private val FormsToCompileKey = catching(classOf[ClassNotFoundException], classOf[NoSuchFieldException]).opt {
    val field = Class.forName("org.jetbrains.jps.uiDesigner.compiler.FormsBuilder").getDeclaredField("FORMS_TO_COMPILE")
    field.setAccessible(true)
    field.get(null).asInstanceOf[Key[util.Map[File, util.Collection[File]]]]
  }

  private def invalidateBoundForms(source: File) {
    FormsToCompileKey.foreach { key =>
      val boundForms: Option[Iterable[File]] = {
        val sourceToForm = context.getProjectDescriptor.dataManager.getSourceToFormMap
        val sourcePath = FileUtil.toCanonicalPath(source.getPath)
        Option(sourceToForm.getState(sourcePath)).map(_.asScala.map(new File(_)))
      }

      boundForms.foreach { forms =>
        val formsToCompile = Option(key.get(context)).getOrElse(new util.HashMap[File, util.Collection[File]]())
        formsToCompile.put(source, forms.toVector.asJava)
        key.set(context, formsToCompile)
      }
    }
  }
}

class IdeClientScalac(compilerName: String,
                              context: CompileContext,
                              modules: Seq[String],
                              consumer: OutputConsumer,
                              mappingsCallback: Callbacks.Backend,
                              successfullyCompiled: mutable.Set[File])
        extends IdeClient(compilerName, context, modules, consumer) {

  val tempSuccessfullyCompiled: mutable.Set[File] = mutable.Set[File]()

  //logic is taken from org.jetbrains.jps.incremental.java.OutputFilesSink.save
  def generated(source: File, outputFile: File, name: String): Unit = {
    val compiledClass = new LazyCompiledClass(outputFile, source, name)
    val content = compiledClass.getContent
    var isTemp: Boolean = false
    val isClassFile = outputFile.getName.endsWith(".class")

    if (source != null && content != null) {
      val sourcePath: String = FileUtil.toSystemIndependentName(source.getPath)
      val rootDescriptor = context.getProjectDescriptor.getBuildRootIndex.findJavaRootDescriptor(context, source)
      if (rootDescriptor != null) {
        isTemp = rootDescriptor.isTemp
        if (!isTemp) {
          try {
            if (isClassFile) consumer.registerCompiledClass(rootDescriptor.target, compiledClass)
            else consumer.registerOutputFile(rootDescriptor.target, outputFile, Collections.singleton[String](sourcePath))
          }
          catch {
            case e: IOException => context.processMessage(new CompilerMessage(compilerName, e))
          }
        }
      }
      if (!isTemp && isClassFile && !Utils.errorsDetected(context)) {
        try {
          val reader: ClassReader = new ClassReader(content.getBuffer, content.getOffset, content.getLength)
          mappingsCallback.associate(FileUtil.toSystemIndependentName(outputFile.getPath), sourcePath, reader)
        }
        catch {
          case e: Throwable =>
            val message: String = "Class dependency information may be incomplete! Error parsing generated class " + outputFile.getPath
            context.processMessage(
              new CompilerMessage(compilerName, BuildMessage.Kind.WARNING, message + "\n" + CompilerMessage.getTextFromThrowable(e), sourcePath))
        }
      }
    }

    if (isClassFile && !isTemp && source != null)
      tempSuccessfullyCompiled += source
  }

  //add source to successfullyCompiled only after the whole file is processed
  def processSource(source: File): Unit = {
    if (tempSuccessfullyCompiled(source)) {
      successfullyCompiled += source
      tempSuccessfullyCompiled -= source
    }
  }
}

// TODO expect future JPS API to load the generated file content lazily (on demand)
private class LazyCompiledClass(outputFile: File, sourceFile: File, className: String)
        extends CompiledClass(outputFile, sourceFile, className, new BinaryContent(Array.empty)){

  private var loadedContent: Option[BinaryContent] = None
  private var contentIsSet = false

  override def getContent = {
    if (contentIsSet) super.getContent else loadedContent.getOrElse {
      val content = new BinaryContent(FileUtil.loadFileBytes(outputFile))
      loadedContent = Some(content)
      content
    }
  }

  override def setContent(content: BinaryContent) {
    super.setContent(content)
    loadedContent = None
    contentIsSet = true
  }
}
