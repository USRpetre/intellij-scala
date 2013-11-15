package org.jetbrains.jps.incremental.scala
package local

import sbt.compiler.{CompilerCache, CompilerArguments, CompileOutput, AnalyzingCompiler}
import org.jetbrains.jps.incremental.scala.data.CompilationData
import xsbti.{Severity, Position}
import java.io.File
import xsbti.api.SourceAPI
import xsbti.compile.DependencyChanges


/**
 * Nikolay.Tropin
 * 11/12/13
 */
class ScalacCompilerImpl(scalac: Option[AnalyzingCompiler]) extends Compiler{
  def compile(compilationData: CompilationData, client: Client): Unit = {
    val progress = ClientUtils.progress(client)
    val reporter = ClientUtils.reporter(client)
    val logger = ClientUtils.logger(client)
    val clientCallback = new AnalysisCallback {
      override def generatedClass(source: File, module: File, name: String) = client.generated(source, module, name)
    }

    for (compiler <- scalac) {
      import compilationData._
      val out = CompileOutput(output)
      val cArgs = new CompilerArguments(compiler.scalaInstance, compiler.cp)
      val options = cArgs(Nil, classpath, None, scalaOptions)
      val emptyChanges: DependencyChanges = new DependencyChanges {
        val modifiedBinaries = new Array[File](0)
        val modifiedClasses = new Array[String](0)
        def isEmpty = true
      }
      compiler.compile(sources, emptyChanges, options, out, clientCallback, reporter, CompilerCache.fresh, logger, Option(progress))
    }
  }

  private class AnalysisCallback extends xsbti.AnalysisCallback {
    def problem(what: String, pos: Position, msg: String, severity: Severity, reported: Boolean) {}
    def api(sourceFile: File, source: SourceAPI) {}
    def endSource(sourcePath: File) {}
    def generatedClass(source: File, module: File, name: String) = {}
    def binaryDependency(binary: File, name: String, source: File, publicInherited: Boolean) {}
    def sourceDependency(dependsOn: File, source: File, publicInherited: Boolean) {}
    def beginSource(source: File) {}
  }
}
