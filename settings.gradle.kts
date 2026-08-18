plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "EuroTransit-application"

include("catalog")
include("observability")
include("orders")
include("inventory")
include("notifications")
include("payments")
include("security-support")
include("money-path-contracts")
