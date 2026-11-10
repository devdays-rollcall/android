import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    // Виртуальное время kotlinx-coroutines-test (advanceTimeBy, UnconfinedTestDispatcher) помечено как экспериментальное.
    target.compilations.matching { it.name == "test" || it.name == "testFixtures" }.configureEach {
        compileTaskProvider.configure {
            compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)
    implementation(libs.kotlinx.serialization.json)

    // Общий контрактный тест хранилища: его наследуют тесты InMemoryMeshStore здесь и RoomMeshStore в app.
    testFixturesApi(libs.junit)
    testFixturesApi(libs.kotlin.test)
    testFixturesApi(libs.kotlinx.coroutines.test)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
