package dev.vibecloud.core.server

class UnsupportedServerTypeException(message: String) : IllegalArgumentException(message)

class ServiceStartException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class CloudShuttingDownException :
    IllegalStateException("The cloud is shutting down and no longer accepts service operations")

class GroupInUseException(message: String) : IllegalStateException(message)
