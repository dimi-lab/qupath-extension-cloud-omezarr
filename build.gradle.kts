import java.util.Locale

plugins {
  // To optionally create a shadow/fat jar that bundle up any non-core dependencies
  id("com.gradleup.shadow") version "8.3.5"
  // QuPath Gradle extension convention plugin
  id("qupath-conventions")

  kotlin("jvm") version "2.1.10"
  `maven-publish`
}

java {
  withSourcesJar()
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
    }
  }
  repositories {
    maven {
      name = "GitHubPackages"
      url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY")}")
      credentials {
        username = System.getenv("GITHUB_ACTOR")
        password = System.getenv("GITHUB_TOKEN")
      }
    }
  }
}

repositories {
  mavenCentral()

  maven {
    name = "Maven SciJava"
    url = uri("https://maven.scijava.org/content/groups/public")
  }
}

qupathExtension {
  name = "qupath-extension-cloud-omezarr"
  group = "io.github.dimilab"
  version = "0.1.0-SNAPSHOT"
  description = "QuPath extension to load OME-Zarr images from cloud storage"
  automaticModule = "io.github.dimi-lab.qupath.extension.cloud-omezarr"
}

tasks.named("build") {
  dependsOn("shadowJar")
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  implementation(kotlin("stdlib-jdk8"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

  // For loading cblosc
  implementation("net.java.dev.jna:jna:5.16.0")

  // Main dependencies for most QuPath extensions
  shadow(libs.bundles.qupath)
  shadow(libs.bundles.logging)
  shadow(libs.qupath.fxtras)

  // OME + Zarr
  implementation("ome:formats-api:8.1.0")
  implementation("ome:formats-common:5.2.4")
  implementation("org.openmicroscopy:ome-model:6.4.0")
  implementation("dev.zarr:jzarr:0.4.2")

//  val cbloscArch = let {
//    val isWindows = System.getProperty("os.name").lowercase(Locale.ENGLISH).contains("win")
//    val isUnixish = System.getProperty("os.name").lowercase(Locale.ENGLISH).contains("nix") || System.getProperty("os.name").lowercase(Locale.ENGLISH).contains("nux")
//    val isAppleArch = System.getProperty("os.arch").equals("aarch64", ignoreCase = true)
//
//    when {
//      isWindows -> "win32-x86_64"
//      isUnixish ->
//        if (isAppleArch) "linux-aarch64" else "linux-x86_64"
//      System.getProperty("os.name").lowercase(Locale.ENGLISH).contains("mac") ->
//        if (isAppleArch) "darwin-aarch64" else "darwin-x86_64"
//      else -> ""
//    }
//  }
//  implementation("io.github.qupath:blosc:1.21.6+01:${cbloscArch}")
  implementation("io.github.qupath:blosc:1.21.6+01")

  // Google Cloud Storage
  implementation(platform("com.google.cloud:libraries-bom:26.43.0"))
  implementation("com.google.cloud:google-cloud-nio")
  implementation("com.google.cloud:google-cloud-storage")

  // Other / utility
  implementation("commons-cli:commons-cli:1.9.0")
  implementation("commons-io:commons-io:2.16.1")

  // For testing
  testImplementation(libs.bundles.qupath)
  testImplementation(kotlin("test"))
  testImplementation("io.mockk:mockk:1.13.17")
}

repositories {
  mavenCentral()
}

tasks.withType<Test> {
  useJUnitPlatform()
}
