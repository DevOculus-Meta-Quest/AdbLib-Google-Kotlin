plugins {
    kotlin("jvm") version "1.9.0" apply false
    id("com.android.application") version "8.5.0" apply false
    id("com.android.library") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
}

task<Delete>("clean") {
    delete(rootProject.buildDir)
}
