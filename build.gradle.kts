plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    kotlin("android") version "2.0.21" apply false
}

// Consumed by the Android module provided by Capacitor.
extra["compileSdkVersion"] = 35

for (role in listOf("pos", "pay")) {
    val title = role.replaceFirstChar { it.uppercase() }
    tasks.register<Exec>("build${title}Web") {
        workingDir(rootDir)
        commandLine("node", "node_modules/vite/bin/vite.js", "build", "--mode", role)
        inputs.files("package-lock.json", "vite.config.js")
        inputs.dir("web")
        outputs.dir(layout.buildDirectory.dir("web/$role"))
    }
}
