plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("maven-publish")
}

val projectVersion: String by project

group = "dev.voqal"
version = project.properties["frameworkVersion"] as String? ?: projectVersion

configure<PublishingExtension> {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/voqal/voqal-framework")
            credentials {
                username = System.getenv("GH_PUBLISH_USERNAME")?.toString()
                password = System.getenv("GH_PUBLISH_TOKEN")?.toString()
            }
        }
    }
}

repositories {
    mavenCentral()
    maven("https://s01.oss.sonatype.org/content/repositories/releases/")
    maven("https://www.jetbrains.com/intellij-repository/releases/")
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
}

dependencies {
}

kotlin {
    jvm()
    js {
        browser()
        binaries.executable()
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                compileOnly(libs.openai.client)
                compileOnly(libs.vertx.core)
                compileOnly(libs.vertx.lang.kotlin.coroutines)
                compileOnly(libs.ktor.client.content.negotiation)
                compileOnly(libs.ktor.serialization.kotlinx.json)
                compileOnly(libs.pebble)
                compileOnly(libs.libfvad.jni)
                compileOnly(libs.vertexai)
                compileOnly(libs.commons.lang3)
                compileOnly(libs.commons.io)
                compileOnly(libs.jna)
                compileOnly(libs.snakeyaml)
                compileOnly("io.github.funnysaltyfish:partial-json-parser:1.0.2")
                compileOnly("io.github.oshai:kotlin-logging:7.0.3")
                implementation("com.google.api-client:google-api-client:2.7.1")
                implementation("com.google.oauth-client:google-oauth-client-jetty:1.37.0")
                implementation("com.google.apis:google-api-services-gmail:v1-rev20220404-2.0.0")
                implementation("javax.mail:mail:1.4.7")
                implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
                implementation("io.dropwizard.metrics:metrics-core:4.2.29")

                compileOnly("com.jetbrains.intellij.platform:code-style:242.23726.103") {
                    isTransitive = false
                }
                compileOnly("com.jetbrains.intellij.platform:core:242.23726.103") {
                    isTransitive = false
                }
                compileOnly("com.jetbrains.intellij.platform:diff:242.23726.103") {
                    isTransitive = false
                }
                compileOnly("com.jetbrains.intellij.platform:diff-impl:242.23726.103") {
                    isTransitive = false
                }
                compileOnly("com.jetbrains.intellij.platform:editor:242.23726.103") {
                    isTransitive = false
                }
                compileOnly("com.jetbrains.intellij.platform:extensions:242.23726.103") {
                    isTransitive = false
                }
                compileOnly("com.jetbrains.intellij.platform:util:242.23726.103") {
                    isTransitive = false
                }
                compileOnly("com.jetbrains.intellij.platform:util-base:242.23726.103") {
                    isTransitive = false
                }
                compileOnly("com.jetbrains.intellij.platform:util-diff:242.23726.103") {
                    isTransitive = false
                }
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("org.slf4j:slf4j-simple:2.0.16")
                implementation("io.vertx:vertx-junit5:4.5.11")
                implementation("org.jooq:joor:0.9.15")

                implementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
                runtimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
                implementation(libs.mockito.kotlin)
                implementation("io.ktor:ktor-server-websockets:2.3.13")
                implementation("io.ktor:ktor-server-netty:2.3.13")

                implementation("io.ktor:ktor-client-java:2.3.13")
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.java.jvm)
                implementation(libs.ktor.serialization.kotlinx.json)

                implementation(libs.openai.client)
                implementation(libs.vertx.core)
                implementation(libs.vertx.lang.kotlin.coroutines)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.pebble)
                implementation(libs.libfvad.jni)
                implementation(libs.vertexai)
                implementation(libs.commons.lang3)
                implementation(libs.commons.io)
                implementation(libs.jna)
                implementation(libs.snakeyaml)
                implementation("io.github.funnysaltyfish:partial-json-parser:1.0.2")
                implementation("io.github.oshai:kotlin-logging:7.0.3")

                val intellijVersion = "242.23726.103"
                implementation("com.jetbrains.intellij.platform:diagnostic:$intellijVersion") {
                    isTransitive = false
                }
                implementation("com.jetbrains.intellij.platform:code-style:$intellijVersion") {
                    isTransitive = false
                }
                implementation("com.jetbrains.intellij.platform:core:$intellijVersion") {
                    isTransitive = false
                }
                implementation("com.jetbrains.intellij.platform:diff:$intellijVersion") {
                    isTransitive = false
                }
                implementation("com.jetbrains.intellij.platform:diff-impl:$intellijVersion") {
                    isTransitive = false
                }
                implementation("com.jetbrains.intellij.platform:editor:$intellijVersion") {
                    isTransitive = false
                }
                implementation("com.jetbrains.intellij.platform:extensions:$intellijVersion") {
                    isTransitive = false
                }
                implementation("com.jetbrains.intellij.platform:util:$intellijVersion") {
                    isTransitive = false
                }
                implementation("com.jetbrains.intellij.platform:util-base:$intellijVersion") {
                    isTransitive = false
                }
                implementation("com.jetbrains.intellij.platform:util-diff:$intellijVersion") {
                    isTransitive = false
                }
                implementation("com.jetbrains.intellij.platform:util-rt:$intellijVersion") {
                    isTransitive = false
                }
                implementation("org.jetbrains.intellij.deps.fastutil:intellij-deps-fastutil:8.5.13") {
                    isTransitive = false
                }
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
