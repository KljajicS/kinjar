package app.kinjar.domain

class LocalHistoryChanged : IllegalStateException("Local data changed; confirm replacement again")
