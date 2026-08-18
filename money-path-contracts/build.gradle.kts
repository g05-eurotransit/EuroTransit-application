plugins {
    id("library-conventions")
}

// Pure wire contracts shared across money-path services (orders, inventory, payments,
// notifications). Deliberately free of Spring/framework dependencies: only JDK/Kotlin
// types, so any service can depend on it without pulling infrastructure.
