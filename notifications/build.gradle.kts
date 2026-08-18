plugins {
    id("services-conventions")
}

dependencies {
    implementation(project(":observability"))
    implementation(project(":money-path-contracts"))
}
