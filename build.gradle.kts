import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    `java-library`
    `maven-publish`
}

group = "top.fpsmaster"
version = "0.1.1"

dependencies {
    // 唯一的第三方运行时依赖：Gson（JSON 解析）。其余全部走 JDK 内置。
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(kotlin("test"))
}

// 目标字节码 JVM 1.8：本库要被 Minecraft 1.8.9 / Forge（Java 8）的 mod 直接引用。
// 不用 toolchain，直接用当前运行 Gradle 的 JDK（21）编译成 Java 8 字节码，避免额外下载 JDK；
// -Xjdk-release=1.8 保证不会误用任何 Java 9+ API（如 java.net.http）。
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "music-api"
            from(components["java"])
        }
    }
}
