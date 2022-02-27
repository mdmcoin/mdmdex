description := "Node integration extension for the Waves DEX"

import java.io.FilenameFilter

import ImageVersionPlugin.autoImport.{imageTagMakeFunction, nameOfImage}
import VersionSourcePlugin.V
import WavesNodeArtifactsPlugin.autoImport.wavesNodeVersion
import com.typesafe.sbt.SbtNativePackager.Universal
import sbtdocker.DockerPlugin.autoImport._

enablePlugins(
  RunApplicationSettings,
  WavesNodeArtifactsPlugin,
  ExtensionPackaging,
  GitVersioning,
  VersionSourcePlugin,
  sbtdocker.DockerPlugin,
  ImageVersionPlugin
)

V.scalaPackage := "com.wavesplatform.dex.grpc.integration"
V.subProject := "ext"

libraryDependencies ++= Dependencies.Module.wavesExt

val packageSettings = Seq(
  maintainer := "wavesplatform.com",
  packageSummary := "Node integration extension for the Waves DEX",
  packageDescription := s"${packageSummary.value}. Compatible with ${wavesNodeVersion.value} node version"
)

packageSettings
inScope(Global)(packageSettings)

lazy val versionSourceTask = Def.task {

  val versionFile      = sourceManaged.value / "com" / "wavesplatform" / "dex" / "grpc" / "integration" / "Version.scala"
  val versionExtractor = """(\d+)\.(\d+)\.(\d+).*""".r

  val (major, minor, patch) = version.value match {
    case versionExtractor(ma, mi, pa) => (ma.toInt, mi.toInt, pa.toInt)
    case x                            =>
      // SBT downloads only the latest commit, so "version" doesn't know, which tag is the nearest
      if (Option(System.getenv("TRAVIS")).fold(false)(_.toBoolean)) (0, 0, 0)
      else throw new IllegalStateException(s"ext: can't parse version by git tag: $x")
  }

  IO.write(
    versionFile,
    s"""package com.wavesplatform.dex.grpc.integration
       |
       |object Version {
       |  val VersionString = "${version.value}"
       |  val VersionTuple = ($major, $minor, $patch)
       |}
       |""".stripMargin
  )
  Seq(versionFile)
}

inConfig(Compile)(Seq(
  sourceGenerators += versionSourceTask,
  unmanagedJars := (Compile / unmanagedJars).dependsOn(downloadWavesNodeArtifacts).value
))

// Packaging
executableScriptName := "tn-dex-extension"

// Add waves-grpc's JAR, dependency modules are ignored by ExtensionPackaging plugin
classpathOrdering += ExtensionPackaging.linkedProjectJar(
  jar = (LocalProject("waves-grpc") / Compile / packageBin).value,
  art = (LocalProject("waves-grpc") / Compile / packageBin / artifact).value,
  moduleId = (LocalProject("waves-grpc") / projectID).value
)

// Exclude waves-all*.jar
Runtime / dependencyClasspath := {
  val exclude = (Compile / unmanagedJars).value.toSet
  (Runtime / dependencyClasspath).value.filterNot(exclude.contains)
}

// ZIP archive
inConfig(Universal)(
  Seq(
    packageName := s"tn-dex-extension-${version.value}", // An archive file name
    topLevelDirectory := None
  )
)

// DEB package
inConfig(Linux)(
  Seq(
    name := s"tn-dex-extension${network.value.packageSuffix}", // A staging directory name
    normalizedName := name.value, // An archive file name
    packageName := name.value // In a control file
  )
)
inTask(docker)(
  Seq(
    nameOfImage := "turtlenetwork/matcher-node",
    imageTagMakeFunction := (gitTag => s"${wavesNodeVersion.value}_$gitTag"),
    dockerfile := {
      val log = streams.value.log

      val grpcServerDir = unmanagedBase.value
        .listFiles(new FilenameFilter {
          override def accept(dir: File, name: String): Boolean = name.startsWith("waves-grpc-server") && name.endsWith(".tgz")
        })
        .headOption match {
        case None => throw new RuntimeException("Can't find the grcp-server archive")
        case Some(grpcServerArchive) =>
          val targetDir = (Compile / compile / target).value
          val r = targetDir / grpcServerArchive.getName.replace(".tgz", "")
          if (!r.isDirectory) {
            log.info(s"Decompressing $grpcServerArchive to $targetDir")
            MatcherIOUtils.decompressTgz(grpcServerArchive, targetDir)
          }
          r
      }

      new Dockerfile {
        from(s"turtlenetwork/tnnode:${wavesNodeVersion.value}")
        user("143:143") // waves:waves
        add(
          sources = Seq((Universal / stage).value / "lib", grpcServerDir / "lib"),
          destination = "/usr/share/TN/lib/plugins/",
          chown = "143:143"
        )
        expose(6887, 6881, 6871) // DEX Extension, Blockchain Updates Extension, Stagenet REST API
      }
    },
    buildOptions := BuildOptions(removeIntermediateContainers = BuildOptions.Remove.OnSuccess)
  )
)