package net.lmoriarty.scanner

class DataExtractException : Exception {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

class MakeMeHostConnectException(message: String, cause: Throwable) : Exception(message, cause)