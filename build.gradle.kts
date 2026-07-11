tasks.register("runGui") {
    group = "application"
    description = "Runs the Mission Recorder Compose desktop GUI."
    dependsOn(":desktop-app:run")
}
