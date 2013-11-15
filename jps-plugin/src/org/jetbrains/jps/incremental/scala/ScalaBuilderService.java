package org.jetbrains.jps.incremental.scala;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.ModuleChunk;
import org.jetbrains.jps.builders.BuildOutputConsumer;
import org.jetbrains.jps.builders.BuildTargetType;
import org.jetbrains.jps.builders.DirtyFilesHolder;
import org.jetbrains.jps.builders.java.JavaSourceRootDescriptor;
import org.jetbrains.jps.cmdline.ProjectDescriptor;
import org.jetbrains.jps.incremental.*;
import org.jetbrains.jps.incremental.scala.model.CompilerType;
import org.jetbrains.jps.incremental.scala.model.GlobalSettings;
import org.jetbrains.jps.incremental.scala.model.Order;
import org.jetbrains.jps.model.JpsProject;
import org.jetbrains.jps.model.java.JpsJavaExtensionService;
import org.jetbrains.jps.model.module.JpsModule;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Pavel Fatin
 */
public class ScalaBuilderService extends BuilderService {
  @NotNull
  @Override
  public List<? extends ModuleLevelBuilder> createModuleLevelBuilders() {
    List<ScalaBuilderDecorator> result = new ArrayList<ScalaBuilderDecorator>(2);
    result.add(new ScalaBuilderDecorator(BuilderCategory.SOURCE_PROCESSOR)); //for compile order Scala then Java or Mixed
    result.add(new ScalaBuilderDecorator(BuilderCategory.OVERWRITING_TRANSLATOR)); //for compile order Java then Scala
    return result;
  }

  @NotNull
  @Override
  public List<? extends TargetBuilder<?, ?>> createBuilders() {
    return Collections.singletonList(new StubTargetBuilder());
  }

  private static class ScalaBuilderDecorator extends ScalaBuilder {

    public ScalaBuilderDecorator(BuilderCategory builderCategory) {
      super(builderCategory);
    }

    @Override
    public ExitCode build(CompileContext context,
                          ModuleChunk chunk,
                          DirtyFilesHolder<JavaSourceRootDescriptor, ModuleBuildTarget> dirtyFilesHolder,
                          OutputConsumer outputConsumer) {
      ProjectDescriptor projectDescriptor = context.getProjectDescriptor();

      //turn off one of the builders
      GlobalSettings globalSettings = SettingsManager.getGlobalSettings(projectDescriptor.getModel().getGlobal());
      Order order = globalSettings.getCompileOrder();
      if (order == Order.JavaThenScala && getCategory() != BuilderCategory.OVERWRITING_TRANSLATOR)
        return ExitCode.NOTHING_DONE;
      if (order != Order.JavaThenScala && getCategory() == BuilderCategory.OVERWRITING_TRANSLATOR)
        return ExitCode.NOTHING_DONE;

      JpsProject project = projectDescriptor.getProject();
      return isScalaProject(project)
          ? super.build(context, chunk, dirtyFilesHolder, outputConsumer)
          : ExitCode.NOTHING_DONE;
    }
  }

  // TODO expect future JPS API to provide a more elegant way to substitute default Java compiler
  private static class StubTargetBuilder extends TargetBuilder<JavaSourceRootDescriptor, ModuleBuildTarget> {
    public StubTargetBuilder() {
      super(Collections.<BuildTargetType<ModuleBuildTarget>>emptyList());
    }

    @Override
    public void buildStarted(CompileContext context) {
      JpsProject project = context.getProjectDescriptor().getProject();
      GlobalSettings settings = SettingsManager.getGlobalSettings(context.getProjectDescriptor().getModel().getGlobal());

      // Disable default Java compiler for a project with Scala facets for sbt compiler
      if (isScalaProject(project) && settings.getCompilerType() == CompilerType.SBT) {
        JpsJavaExtensionService.getInstance()
            .getOrCreateCompilerConfiguration(project)
            .setJavaCompilerId("scala");
      }
    }

    @Override
    public void build(@NotNull ModuleBuildTarget target,
                      @NotNull DirtyFilesHolder<JavaSourceRootDescriptor, ModuleBuildTarget> holder,
                      @NotNull BuildOutputConsumer outputConsumer,
                      @NotNull CompileContext context) throws ProjectBuildException, IOException {
      // do nothing
    }

    @NotNull
    @Override
    public String getPresentableName() {
      return "Scala Stub Builder";
    }
  }

  private static boolean isScalaProject(JpsProject project) {
    for (JpsModule module : project.getModules()) {
      if (SettingsManager.getFacetSettings(module) != null) {
        return true;
      }
    }
    return false;
  }
}
