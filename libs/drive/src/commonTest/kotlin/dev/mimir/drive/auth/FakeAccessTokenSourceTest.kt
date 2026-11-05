package dev.mimir.drive.auth

class FakeAccessTokenSourceTest : AccessTokenSourceContract() {
    override val storedToken = "t1"
    override fun signedIn(): AccessTokenSource = FakeAccessTokenSource(ArrayDeque(listOf("t1", "t2")))
    override fun signedOut(): AccessTokenSource = FakeAccessTokenSource(ArrayDeque(), requireSignIn = true)
}
