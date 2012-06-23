package no.vedaadata.sbtjavafx

import sbt._
import Keys._
import classpath.ClasspathUtilities
import org.apache.tools.ant

case class Output(artifactBaseName: (String, ModuleID, Artifact) => String, artifactBaseNameValue: String, deployDir: Option[String])

case class Template(file: Option[String], destFile: Option[String], placeholderId: String)

case class Permissions(elevated: Boolean, cacheCertificates: Boolean)

case class Signing(keyStore: Option[File], storePass: Option[String], alias: Option[String], keyPass: Option[String], storeType: Option[String])

case class Dimensions(width: Int, height: Int, embeddedWidth: String, embeddedHeight: String)

case class JFX(
  sdkDir: String,
  mainClass: String,
  javaOnly: Boolean,
  output: Output,
  template: Template,
  dimensions: Dimensions,
  permissions: Permissions,
  signing: Signing)

object JavaFXPlugin extends Plugin {

  //	Define the keys

  private def dashed(a: String, b: String) = List(a, "-", b).mkString

  val jfx = SettingKey[JFX]("javafx", "All JavaFX settings.")

  object JFX {

    private def prefixed(name: String) = dashed(jfx.key.label, name)

    val sdkDir = SettingKey[String](prefixed("sdk-dir"), "Location of JavaFX SDK.")

    val mainClass = SettingKey[String](prefixed("main-class"), "Entry point for JavaFX application, must extend javafx.application.Application and implement the start() method.")

    val javaOnly = SettingKey[Boolean](prefixed("java-only"), "Convenience setting for JavaFX applications in pure Java, sets some other settings to usable defaults for this scenario.")

    val template = SettingKey[Template](prefixed("template"), "JavaFX HTML template settings.")

    val output = SettingKey[Output](prefixed("output"), "JavaFX output settings.")

    object Output {
      private def prefixed(name: String) = dashed(output.key.label, name)
      val artifactBaseName = SettingKey[(String, ModuleID, Artifact) => String](prefixed("artifact-base-name"), "Function that produces the JavaFX artifact name (without file extension) from its definition.")
      val artifactBaseNameValue = SettingKey[String](prefixed("artifact-base-name-value"), "The actual name of the JavaFX artifact (without file extension).")
      val deployDir = SettingKey[Option[String]](prefixed("deploy-dir"), "Directory the packaged application will be copied to when executing the 'deploy' task.")
    }

    object Template {
      private def prefixed(name: String) = dashed(template.key.label, name)
      val file = SettingKey[Option[String]](prefixed("file"), "HTML template input file.")
      val destFile = SettingKey[Option[String]](prefixed("dest-file"), "HTML template output file.")
      val placeholderId = SettingKey[String](prefixed("placeholder-id"), "HTML template placeholder id.")
    }

    val dimensions = SettingKey[Dimensions](prefixed("dimensions"), "JavaFX dimensions settings.")

    object Dimensions {
      private def prefixed(name: String) = dashed(dimensions.key.label, name)
      val width = SettingKey[Int](prefixed("width"), "JavaFX application width.")
      val height = SettingKey[Int](prefixed("height"), "JavaFX application height.")
      val embeddedWidth = SettingKey[String](prefixed("embedded-width"), "JavaFX applet width.")
      val embeddedHeight = SettingKey[String](prefixed("embedded-height"), "JavaFX applet height.")
    }

    val permissions = SettingKey[Permissions](prefixed("permissions"), "JavaFX application permission settings.")

    object Permissions {
      private def prefixed(name: String) = dashed(permissions.key.label, name)
      val elevated = SettingKey[Boolean](prefixed("elevated"), "Whether this JavaFX application requires elevated permissions.")
      val cacheCertificates = SettingKey[Boolean](prefixed("cache-certificates"), "Whether the signing certificates should be cached in the deployment descriptor.")
    }

    val signing = SettingKey[Signing](prefixed("signing"), "Settings for JavaFX jar signing.")

    object Signing {
      private def prefixed(name: String) = dashed(signing.key.label, name)
      val keyStore = SettingKey[Option[File]](prefixed("key-store"), "Filename for keystore for jar signing.")
      val storePass = SettingKey[Option[String]](prefixed("store-pass"), "Password for keystore for jar signing.")
      val alias = SettingKey[Option[String]](prefixed("alias"), "Key name for jar signing.")
      val keyPass = SettingKey[Option[String]](prefixed("key-pass"), "Key password for jar signing.")
      val storeType = SettingKey[Option[String]](prefixed("store-type"), "Keytype store for signing.")
    }

    val packageJavaFx = TaskKey[Unit]("package-javafx", "Packages a JavaFX application.")

    val deploy = TaskKey[Unit]("deploy", "Deploys a JavaFX application.")

  }

  //	Define the packaging task

  val packageJavaFxTask = (name, classDirectory in Compile, fullClasspath in Runtime, baseDirectory, crossTarget, jfx) map {
    (name, classDir, fullClasspath, baseDirectory, crossTarget, jfx) =>

      //	Setup paths and delete anything left over from previous build

      import IO._

      val libDir = crossTarget / "lib"
      val distDir = crossTarget / jfx.output.artifactBaseNameValue

      val jarFile = distDir / (jfx.output.artifactBaseNameValue + ".jar")

      delete(libDir)
      delete(distDir)

      if (distDir.exists && distDir.list.nonEmpty)
        sys.error("Could not delete previous build. Make sure no files are open in " + distDir)

      val templateFile = jfx.template.file map { f =>
        if (file(f).isAbsolute) file(f)
        else (baseDirectory / f)
      }

      val templateDestFile = jfx.template.destFile orElse jfx.template.file map { f =>
        if (file(f).isAbsolute) file(f)
        else (distDir / f)
      }  
      
      //	All library jars that should be packaged with the application

      val libJars = fullClasspath map (_.data) filter ClasspathUtilities.isArchive filterNot (_.getName endsWith "jfxrt.jar")

      //	Copy the jars to temporary lib folder

      val srcToDest = libJars map (src => (src, libDir / src.getName))

      copy(srcToDest)

      //	Check that the JavaFX Ant library is present

      val antLib = jfx.sdkDir + "/lib/ant-javafx.jar"

      if (!file(antLib).exists) sys.error(antLib + " does not exists")

      //	Generate the Ant buildfile

      val antBuildXml =
        <project name={ name } default="default" basedir="." xmlns:fx="javafx:com.sun.javafx.tools.ant">
          <target name="default">
            <taskdef resource="com/sun/javafx/tools/ant/antlib.xml" uri="javafx:com.sun.javafx.tools.ant" classpath={ antLib }/>
            <fx:application id="fxApp" name={ name } mainClass={ jfx.mainClass }/>
            <fx:resources id="fxRes">
              { if (libJars.nonEmpty) <fx:fileset dir={ libDir.getAbsolutePath }/> }
            </fx:resources>
            <fx:jar destfile={ jarFile.getAbsolutePath }>
              <fx:application refid="fxApp"/>
              <fx:fileset dir={ classDir.getAbsolutePath }/>
              <fx:resources refid="fxRes"/>
            </fx:jar>
            {
              if (jfx.permissions.elevated) {
                <fx:signjar destdir={ distDir.getAbsolutePath } keyStore={ jfx.signing.keyStore map (_.getAbsolutePath) getOrElse sys.error("fx-key-store is not defined") } storePass={ jfx.signing.storePass getOrElse sys.error("fx-store-pass is not defined") } alias={ jfx.signing.alias getOrElse sys.error("fx-alias is not defined") } keyPass={ jfx.signing.keyPass getOrElse sys.error("fx-key-pass is not defined") } storeType={ jfx.signing.storeType getOrElse "jks" }>
                  <fx:fileset dir={ distDir.getAbsolutePath }/>
                </fx:signjar>
              }
            }
            {
              if (jfx.permissions.elevated && libJars.nonEmpty) {
                <fx:signjar destdir={ libDir.getAbsolutePath } keyStore={ jfx.signing.keyStore map (_.getAbsolutePath) getOrElse sys.error("fx-key-store is not defined") } storePass={ jfx.signing.storePass getOrElse sys.error("fx-store-pass is not defined") } alias={ jfx.signing.alias getOrElse sys.error("fx-alias is not defined") } keyPass={ jfx.signing.keyPass getOrElse sys.error("fx-key-pass is not defined") } storeType={ jfx.signing.storeType getOrElse "jks" }>
                  <fx:fileset dir={ libDir.getAbsolutePath }/>
                </fx:signjar>
              }
            }
            <fx:deploy width={ jfx.dimensions.width.toString } height={ jfx.dimensions.height.toString } embeddedWidth={ jfx.dimensions.embeddedWidth } embeddedHeight={ jfx.dimensions.embeddedHeight } outdir={ distDir.getAbsolutePath } outfile={ jfx.output.artifactBaseNameValue } placeholderId={ jfx.template.placeholderId }>
              <fx:application refid="fxApp"/>
              <fx:resources>
                <fx:fileset dir={ distDir.getAbsolutePath } includes={ jfx.output.artifactBaseNameValue + ".jar" }/>
                { if (libJars.nonEmpty) <fx:fileset dir={ libDir.getAbsolutePath }/> }
              </fx:resources>
              <fx:permissions elevated={ jfx.permissions.elevated.toString } cacheCertificates={ jfx.permissions.cacheCertificates.toString }/>
              {
                templateFile map { tf =>
                  <fx:template file={ tf.getAbsolutePath } tofile={ templateDestFile map(_.getAbsolutePath) getOrElse tf.getAbsolutePath }/>
                } getOrElse Unit
              }
            </fx:deploy>
          </target>
        </project>

      val buildFile = crossTarget / "build.xml"

      write(buildFile, antBuildXml.toString)

      //	Run the buildfile

      val antProject = new ant.Project

      antProject setUserProperty ("ant.file", buildFile.getAbsolutePath)
      antProject init ()

      val helper = ant.ProjectHelper.getProjectHelper
      helper parse (antProject, buildFile)

      println("Packaging to " + distDir + "...")

      antProject executeTarget "default"
  }

  //	Define the deploy task

  val deployTask = (JFX.packageJavaFx, crossTarget, jfx) map { (packageJavaFx, crossTarget, jfx) =>
    val distDir = crossTarget / jfx.output.artifactBaseNameValue
    val deployDistDir = file(jfx.output.deployDir getOrElse sys.error("deployDir is not defined")) / jfx.output.artifactBaseNameValue
    println("Deploying to " + deployDistDir + "...")
    IO copyDirectory (distDir, deployDistDir, overwrite = true, preserveLastModified = true)
  }

  //	Define the settings

  val jfxRt = JFX.sdkDir map (dir => file(dir + "/rt/lib/jfxrt.jar"))

  val jfxSettings: Seq[Setting[_]] = Seq(
    JFX.javaOnly := false,
    JFX.output <<= (JFX.Output.artifactBaseName, JFX.Output.artifactBaseNameValue, JFX.Output.deployDir) apply Output.apply,
    JFX.template <<= (JFX.Template.file, JFX.Template.destFile, JFX.Template.placeholderId) apply Template.apply,
    JFX.dimensions <<= (JFX.Dimensions.width, JFX.Dimensions.height, JFX.Dimensions.embeddedWidth, JFX.Dimensions.embeddedHeight) apply Dimensions.apply,
    JFX.permissions <<= (JFX.Permissions.elevated, JFX.Permissions.cacheCertificates) apply { Permissions(_, _) },
    JFX.signing <<= (JFX.Signing.keyStore, JFX.Signing.storePass, JFX.Signing.alias, JFX.Signing.keyPass, JFX.Signing.storeType) apply Signing.apply)

  val outputSettings: Seq[Setting[_]] = Seq(
    JFX.Output.artifactBaseName <<= crossPaths(p => (v, id, a) => List(Some(a.name), if (p) Some("_" + v) else None, Some("-" + id.revision)).flatten.mkString),
    JFX.Output.artifactBaseNameValue <<= (scalaVersion, projectID, artifact, JFX.Output.artifactBaseName) apply { (v, id, a, f) => f(v, id, a) },
    JFX.Output.deployDir := None)

  val templateSettings: Seq[Setting[_]] = Seq(
    JFX.Template.file := None,
    JFX.Template.destFile := None,
    JFX.Template.placeholderId := "javafx")

  val dimensionsSettings: Seq[Setting[_]] = Seq(
    JFX.Dimensions.width := 800,
    JFX.Dimensions.height := 600,
    JFX.Dimensions.embeddedWidth := "100%",
    JFX.Dimensions.embeddedHeight := "100%")

  val permissionsSettings: Seq[Setting[_]] = Seq(
    JFX.Permissions.elevated := false,
    JFX.Permissions.cacheCertificates := false)

  val signingSettings: Seq[Setting[_]] = Seq(
    JFX.Signing.keyStore := None,
    JFX.Signing.storePass := None,
    JFX.Signing.alias := None,
    JFX.Signing.keyPass := None,
    JFX.Signing.storeType := None)

  override val settings = jfxSettings ++ outputSettings ++ templateSettings ++ dimensionsSettings ++ permissionsSettings ++ signingSettings ++ Seq(
    mainClass in (Compile, run) <<= (JFX.mainClass, JFX.javaOnly) map ((c, j) => if (j) Some(c) else Some(c + "Launcher")),
    (unmanagedClasspath in Compile) <+= jfxRt,
    (unmanagedClasspath in Runtime) <+= jfxRt,
    autoScalaLibrary <<= JFX.javaOnly(x => !x),
    crossPaths <<= JFX.javaOnly(x => !x),
    fork in run := true,
    jfx <<= (JFX.sdkDir, JFX.mainClass, JFX.javaOnly, JFX.output, JFX.template, JFX.dimensions, JFX.permissions, JFX.signing) apply { new JFX(_, _, _, _, _, _, _, _) },
    JFX.packageJavaFx <<= packageJavaFxTask,
    JFX.deploy <<= deployTask)
}