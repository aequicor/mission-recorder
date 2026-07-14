val missionRecorderVersion = providers.gradleProperty("missionRecorderVersion").get()

require(Regex("""\d+\.\d+\.\d+""").matches(missionRecorderVersion)) {
    "missionRecorderVersion must use the MAJOR.MINOR.PATCH format."
}

allprojects {
    version = missionRecorderVersion
}

tasks.register("runGui") {
    group = "application"
    description = "Runs the Mission Recorder Compose desktop GUI."
    dependsOn(":desktop-app:run")
}

tasks.register("run") {
    group = "application"
    description = "Runs the Mission Recorder Compose desktop application."
    dependsOn(":desktop-app:run")
}
