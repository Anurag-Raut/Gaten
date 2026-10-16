/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * The lookup's decisions, against WaniKani's documented payloads: the cache answers before the
 * network, a refresh that fails falls back to what was cached, and content above what the
 * subscription grants is never handed out - from the network or from the cache.
 *
 * The API is stood in for rather than aimed at a server, so these run on a plain JVM with no socket
 * (and no way to reach a real WaniKani).
 */
class WaniKaniClientTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "wanikani") }

    /** What the client is told the time is; a test moves it to cross a subscription's expiry. */
    private var now = 0L

    private fun client(
        api: WaniKaniApi = fakeApi(),
        credentials: WaniKaniCredentials = FakeCredentials(TOKEN),
        storeDir: File = dir
    ): WaniKaniClient = WaniKaniClient(storeDir, credentials, api) { now }

    @Test
    fun `without a token a lookup asks for one and makes no request`() = runBlocking {
        val api = fakeApi()
        val lookup = client(api, FakeCredentials()).lookup(WaniKaniTarget.Kanji("一"), refresh = false)

        assertEquals(WaniKaniLookup.NeedsToken, lookup)
        assertEquals(emptyList<String>(), api.requests)
    }

    @Test
    fun `a target that is not one kanji is refused without a request`() = runBlocking {
        val api = fakeApi(kanji = WaniKaniJson.collection(WaniKaniJson.kanji()))

        assertEquals(WaniKaniLookup.NoEntry, client(api).lookup(WaniKaniTarget.Kanji("食い"), false))
        assertEquals(WaniKaniLookup.NoEntry, client(api).lookup(WaniKaniTarget.Kanji("A"), false))
        assertEquals(WaniKaniLookup.NoEntry, client(api).lookup(WaniKaniTarget.Kanji(""), false))
        assertEquals(emptyList<String>(), api.requests)
    }

    @Test
    fun `a kanji is fetched once, then answered from the cache with no request`() = runBlocking {
        val api = fakeApi(
            user = paidUser(),
            kanji = WaniKaniJson.collection(WaniKaniJson.kanji()),
            components = WaniKaniJson.collection(WaniKaniJson.radical())
        )
        val client = client(api)
        assertEquals(WaniKaniConnection.Connected(60), client.connect(TOKEN))

        val fresh = client.lookup(WaniKaniTarget.Kanji("一"), false) as WaniKaniLookup.Available
        assertEquals(false, fresh.cached)
        assertEquals(false, fresh.stale)
        assertNull(fresh.cacheFailure)
        val kanji = fresh.subject as WaniKaniSubject.Kanji
        assertEquals("一", kanji.character)
        assertEquals(440, kanji.id)
        assertEquals(listOf("One", "Flat"), kanji.meanings)
        assertEquals(listOf("いち"), kanji.onyomi)
        assertEquals("Ground", kanji.components.single().name)
        assertEquals("一", kanji.components.single().character)

        val requests = api.requests.size
        val cached = client.lookup(WaniKaniTarget.Kanji("一"), false) as WaniKaniLookup.Available
        assertTrue(cached.cached)
        assertEquals(false, cached.stale)
        assertNull(cached.cacheFailure)
        assertEquals("Ground", (cached.subject as WaniKaniSubject.Kanji).components.single().name)
        assertEquals(requests, api.requests.size)
        assertEquals(emptyList<String>(), api.imageRequests)
    }

    @Test
    fun `nothing cached and nothing reachable is a failure, not an empty answer`() = runBlocking {
        val api = fakeApi(failure = WaniKaniFailure.OFFLINE)
        assertEquals(
            WaniKaniLookup.Unreachable(WaniKaniFailure.OFFLINE),
            client(api).lookup(WaniKaniTarget.Kanji("一"), false)
        )
    }

    @Test
    fun `an image-only radical served from the cache fetches nothing at all`() = runBlocking {
        val api = fakeApi(user = paidUser(), subject = WaniKaniJson.radical(id = 1, character = null))
        api.imageHandler = { WaniKaniFetch.Body(SVG_GROUND) }
        val client = client(api)
        client.connect(TOKEN)

        val fresh = client.lookup(WaniKaniTarget.Radical(1), false) as WaniKaniLookup.Available
        assertEquals(SVG_GROUND, (fresh.subject as WaniKaniSubject.Radical).svg)
        assertEquals(1, api.imageRequests.size)

        // The cache read must be answered from the disk alone: no request, and no image request
        // either, however the image itself arrived the first time.
        api.imageHandler = { WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE) }
        val requests = api.requests.size
        val cached = client.lookup(WaniKaniTarget.Radical(1), false) as WaniKaniLookup.Available
        assertTrue(cached.cached)
        assertEquals(SVG_GROUND, (cached.subject as WaniKaniSubject.Radical).svg)
        assertEquals(requests, api.requests.size)
        assertEquals(1, api.imageRequests.size)
    }

    @Test
    fun `an image-only radical whose image is not cached is still shown`() = runBlocking {
        val api = fakeApi(user = paidUser(), subject = WaniKaniJson.radical(id = 1, character = null))
        api.imageHandler = { WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE) }
        val client = client(api)
        client.connect(TOKEN)

        val available = client.lookup(WaniKaniTarget.Radical(1), false) as WaniKaniLookup.Available
        val radical = available.subject as WaniKaniSubject.Radical
        assertNull(radical.svg)
        assertEquals("Ground", radical.primaryMeaning)
        assertEquals(listOf("Ground"), radical.meanings)
        assertNull(available.cacheFailure)
    }

    @Test
    fun `an explicit refresh replaces the cached image`() = runBlocking {
        val api = fakeApi(user = paidUser(), subject = WaniKaniJson.radical(id = 1, character = null))
        api.imageHandler = { WaniKaniFetch.Body("<svg>one</svg>") }
        val client = client(api)
        client.connect(TOKEN)
        client.lookup(WaniKaniTarget.Radical(1), false)

        api.imageHandler = { WaniKaniFetch.Body("<svg>two</svg>") }
        val refreshed = client.lookup(WaniKaniTarget.Radical(1), refresh = true) as WaniKaniLookup.Available
        assertEquals("<svg>two</svg>", (refreshed.subject as WaniKaniSubject.Radical).svg)
        assertEquals(false, refreshed.cached)
        assertEquals(2, api.imageRequests.size)

        api.imageHandler = { WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE) }
        val reused = client.lookup(WaniKaniTarget.Radical(1), false) as WaniKaniLookup.Available
        assertEquals("<svg>two</svg>", (reused.subject as WaniKaniSubject.Radical).svg)
    }

    @Test
    fun `an explicit refresh fetches the components again, not only the missing ones`() = runBlocking {
        val api = fakeApi(
            user = paidUser(),
            kanji = WaniKaniJson.collection(WaniKaniJson.kanji()),
            components = WaniKaniJson.collection(WaniKaniJson.radical(name = "Ground"))
        )
        val client = client(api)
        client.connect(TOKEN)
        client.lookup(WaniKaniTarget.Kanji("一"), false)
        val componentRequests = { api.requests.count { request -> request.contains("ids=") } }
        assertEquals(1, componentRequests())

        api.handler = { path, query ->
            when {
                path == "subjects" && query.any { it.first == "slugs" } ->
                    WaniKaniFetch.Body(WaniKaniJson.collection(WaniKaniJson.kanji()))
                path == "subjects" && query.any { it.first == "ids" } ->
                    WaniKaniFetch.Body(WaniKaniJson.collection(WaniKaniJson.radical(name = "Soil")))
                else -> WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE)
            }
        }
        val refreshed = client.lookup(WaniKaniTarget.Kanji("一"), refresh = true) as WaniKaniLookup.Available
        assertEquals(2, componentRequests())
        assertEquals("Soil", (refreshed.subject as WaniKaniSubject.Kanji).components.single().name)
    }

    @Test
    fun `a refresh that fails falls back to the cached copy, marked stale`() = runBlocking {
        val api = fakeApi(user = paidUser(), kanji = WaniKaniJson.collection(WaniKaniJson.kanji()))
        val client = client(api)
        client.connect(TOKEN)
        client.lookup(WaniKaniTarget.Kanji("一"), false)

        api.handler = { _, _ -> WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE) }
        val stale = client.lookup(WaniKaniTarget.Kanji("一"), refresh = true) as WaniKaniLookup.Available
        assertTrue(stale.cached)
        assertTrue(stale.stale)
        assertEquals("一", (stale.subject as WaniKaniSubject.Kanji).character)
    }

    @Test
    fun `an auth failure is not a miss and is never retried`() = runBlocking {
        val api = fakeApi(kanji = WaniKaniJson.collection(WaniKaniJson.kanji()))
        api.handler = { _, _ -> WaniKaniFetch.Unauthorized }
        assertEquals(WaniKaniLookup.AuthRejected, client(api).lookup(WaniKaniTarget.Kanji("一"), false))
        assertEquals(1, api.requests.size)
    }

    @Test
    fun `an unknown subject and a malformed body mean different things`() = runBlocking {
        val unknown = fakeApi(kanji = WaniKaniJson.collection())
        assertEquals(WaniKaniLookup.NoEntry, client(unknown).lookup(WaniKaniTarget.Kanji("一"), false))

        val malformed = fakeApi(kanji = "<html>nope</html>")
        // A separate store: the malformed answer must be judged on its own, not through a
        // negative record the unknown-subject lookup left behind for the same character.
        val malformedClient = client(malformed, storeDir = File(folder.root, "wanikani-malformed"))
        assertEquals(
            WaniKaniLookup.Unreachable(WaniKaniFailure.MALFORMED),
            malformedClient.lookup(WaniKaniTarget.Kanji("一"), false)
        )
        // A malformed answer is never recorded as "no entry": the second lookup asks again
        // instead of trusting a negative record that was never written.
        assertEquals(
            WaniKaniLookup.Unreachable(WaniKaniFailure.MALFORMED),
            malformedClient.lookup(WaniKaniTarget.Kanji("一"), false)
        )
        assertEquals(2, malformed.requests.size)
    }

    @Test
    fun `a by-id body that is not JSON is a failure, and another kind of subject is a miss`() = runBlocking {
        val malformed = fakeApi(subject = "<html>captive portal</html>")
        assertEquals(
            WaniKaniLookup.Unreachable(WaniKaniFailure.MALFORMED),
            client(malformed).lookup(WaniKaniTarget.Radical(1), false)
        )

        val otherKind = fakeApi(subject = WaniKaniJson.vocabulary(id = 7))
        assertEquals(WaniKaniLookup.NoEntry, client(otherKind).lookup(WaniKaniTarget.Radical(7), false))
        // A subject of a kind Gaten never shows will not become one, so that answer is recorded too.
        assertEquals(WaniKaniLookup.NoEntry, client(otherKind).lookup(WaniKaniTarget.Radical(7), false))
        assertEquals(1, otherKind.requests.size)
    }

    @Test
    fun `a kanji WaniKani has no subject for is not asked about again`() = runBlocking {
        val api = fakeApi(kanji = WaniKaniJson.collection())
        val client = client(api)

        assertEquals(WaniKaniLookup.NoEntry, client.lookup(WaniKaniTarget.Kanji("験"), false))
        assertEquals(1, api.requests.size)

        // Every further tap is answered from the device: WaniKani is not asked twice about the same
        // character it has already said no to.
        assertEquals(WaniKaniLookup.NoEntry, client.lookup(WaniKaniTarget.Kanji("験"), false))
        assertEquals(WaniKaniLookup.NoEntry, client.lookup(WaniKaniTarget.Kanji("験"), false))
        assertEquals(1, api.requests.size)

        // Another character is another question, and is asked.
        assertEquals(WaniKaniLookup.NoEntry, client.lookup(WaniKaniTarget.Kanji("飴"), false))
        assertEquals(2, api.requests.size)
    }

    @Test
    fun `a not-found answer expires, and the character is asked about again`() = runBlocking {
        val api = fakeApi(kanji = WaniKaniJson.collection())
        val client = client(api)
        assertEquals(WaniKaniLookup.NoEntry, client.lookup(WaniKaniTarget.Kanji("験"), false))
        assertEquals(1, api.requests.size)

        // WaniKani has added the subject since. A week later the recorded answer is not trusted any
        // more, so the character is asked about again and found.
        api.handler = { path, _ ->
            if (path == "subjects") {
                WaniKaniFetch.Body(
                    WaniKaniJson.collection(
                        WaniKaniJson.kanji(character = "験", id = 700, componentIds = emptyList())
                    )
                )
            } else {
                WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE)
            }
        }
        now += 8 * 24 * 60 * 60 * 1000L
        val found = client.lookup(WaniKaniTarget.Kanji("験"), false) as WaniKaniLookup.Available
        assertEquals("験", (found.subject as WaniKaniSubject.Kanji).character)
        assertEquals(2, api.requests.size)

        // A found subject, though, is cached for good: a day later it costs nothing.
        now += 24 * 60 * 60 * 1000L
        assertTrue(client.lookup(WaniKaniTarget.Kanji("験"), false) is WaniKaniLookup.Available)
        assertEquals(2, api.requests.size)
    }

    @Test
    fun `a missing subject id is not asked about again`() = runBlocking {
        val api = fakeApi()
        api.handler = { _, _ -> WaniKaniFetch.NotFound }
        val client = client(api)

        assertEquals(WaniKaniLookup.NoEntry, client.lookup(WaniKaniTarget.Radical(7), false))
        assertEquals(WaniKaniLookup.NoEntry, client.lookup(WaniKaniTarget.Radical(7), false))
        assertEquals(1, api.requests.size)

        assertEquals(WaniKaniLookup.NoEntry, client.lookup(WaniKaniTarget.Radical(8), false))
        assertEquals(2, api.requests.size)
    }

    @Test
    fun `a corrupt or future-dated not-found record is a miss, so the character is asked about again`() = runBlocking {
        val api = fakeApi(kanji = WaniKaniJson.collection())
        val client = client(api)
        assertEquals(WaniKaniLookup.NoEntry, client.lookup(WaniKaniTarget.Kanji("験"), false))
        assertEquals(1, api.requests.size)

        File(dir, "missing.tsv").writeText("c:験\tnot a day\n")
        assertEquals(WaniKaniLookup.NoEntry, client.lookup(WaniKaniTarget.Kanji("験"), false))
        assertEquals(2, api.requests.size)

        // A stamp from the future is treated as no record at all rather than as an eternal no.
        File(dir, "missing.tsv").writeText("c:験\t${Long.MAX_VALUE}\n")
        assertEquals(WaniKaniLookup.NoEntry, client.lookup(WaniKaniTarget.Kanji("験"), false))
        assertEquals(3, api.requests.size)
    }

    @Test
    fun `content above the granted level is never served, and never kept in the cache`() = runBlocking {
        val api = fakeApi(
            user = paidUser(),
            kanji = WaniKaniJson.collection(WaniKaniJson.kanji(character = "語", id = 500, level = 5))
        )
        val client = client(api)
        assertEquals(WaniKaniConnection.Connected(60), client.connect(TOKEN))
        assertTrue(client.lookup(WaniKaniTarget.Kanji("語"), false) is WaniKaniLookup.Available)

        // The subscription now grants the free levels only, and the cached copy is above them.
        api.handler = { path, _ ->
            if (path == "user") WaniKaniFetch.Body(freeUser()) else WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE)
        }
        assertEquals(WaniKaniConnection.Connected(3), client.reconnect())
        assertEquals(WaniKaniLookup.Restricted, client.lookup(WaniKaniTarget.Kanji("語"), false))
    }

    @Test
    fun `a restricted subject is refused straight from the network too`() = runBlocking {
        val api = fakeApi(
            user = freeUser(),
            kanji = WaniKaniJson.collection(WaniKaniJson.kanji(character = "語", id = 500, level = 5))
        )
        val client = client(api)
        client.connect(TOKEN)

        assertEquals(WaniKaniLookup.Restricted, client.lookup(WaniKaniTarget.Kanji("語"), false))
        // Nothing above the grant is cached either, so a later paid check cannot be answered from it.
        assertNull(WaniKaniStore(dir).subject(500))
    }

    @Test
    fun `a component above the granted level keeps its id but loses its name`() = runBlocking {
        val api = fakeApi(
            user = paidUser(),
            kanji = WaniKaniJson.collection(WaniKaniJson.kanji(componentIds = listOf(1))),
            components = WaniKaniJson.collection(WaniKaniJson.radical(id = 1, level = 5))
        )
        val client = client(api)
        client.connect(TOKEN)
        val paid = client.lookup(WaniKaniTarget.Kanji("一"), false) as WaniKaniLookup.Available
        assertEquals("Ground", (paid.subject as WaniKaniSubject.Kanji).components.single().name)

        api.handler = { path, _ ->
            if (path == "user") WaniKaniFetch.Body(freeUser()) else WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE)
        }
        client.reconnect()
        val free = client.lookup(WaniKaniTarget.Kanji("一"), false) as WaniKaniLookup.Available
        val component = (free.subject as WaniKaniSubject.Kanji).components.single()
        assertEquals(1, component.id)
        assertNull(component.name)
        assertNull(component.character)
    }

    @Test
    fun `an expired recurring subscription stops granting the paid levels offline`() = runBlocking {
        val api = fakeApi(
            user = WaniKaniJson.user(type = "recurring", periodEndsAt = EXPIRY),
            kanji = WaniKaniJson.collection(WaniKaniJson.kanji(character = "語", id = 500, level = 5))
        )
        val client = client(api)
        val expiry = Instant.parse(EXPIRY).toEpochMilli()

        now = expiry - 1
        assertEquals(WaniKaniConnection.Connected(60), client.connect(TOKEN))
        assertTrue(client.lookup(WaniKaniTarget.Kanji("語"), false) is WaniKaniLookup.Available)

        // The period ends while the app is offline. The cached entitlement must stop granting the
        // paid levels on its own, and the cached subject above them must not be served.
        now = expiry
        api.handler = { _, _ -> WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE) }
        val requests = api.requests.size
        assertEquals(WaniKaniLookup.Restricted, client.lookup(WaniKaniTarget.Kanji("語"), false))
        assertEquals(requests, api.requests.size)

        // A cached subject within the free levels is still readable offline.
        val store = WaniKaniStore(dir)
        assertTrue(store.saveSubject(440, WaniKaniJson.kanji()))
        assertTrue(store.putIndex("一", 440))
        val withinFree = client.lookup(WaniKaniTarget.Kanji("一"), false) as WaniKaniLookup.Available
        assertTrue(withinFree.cached)
        assertEquals("一", (withinFree.subject as WaniKaniSubject.Kanji).character)
    }

    @Test
    fun `connecting stores the token only when the check works`() = runBlocking {
        val rejected = fakeApi()
        rejected.handler = { _, _ -> WaniKaniFetch.Unauthorized }
        val credentials = FakeCredentials()
        assertEquals(WaniKaniConnection.InvalidToken, client(rejected, credentials).connect(TOKEN))
        assertNull(credentials.read())

        val offline = FakeCredentials()
        assertEquals(
            WaniKaniConnection.Unavailable(WaniKaniFailure.OFFLINE),
            client(fakeApi(failure = WaniKaniFailure.OFFLINE), offline).connect(TOKEN)
        )
        assertNull(offline.read())

        // A token that cannot be carried in a header is refused before any request is made.
        val malformed = fakeApi(user = paidUser())
        assertEquals(WaniKaniConnection.InvalidToken, client(malformed, FakeCredentials()).connect("two words"))
        assertEquals(emptyList<String>(), malformed.requests)
    }

    @Test
    fun `connecting reports a token that could not be stored, and leaves nothing half-saved`() = runBlocking {
        val api = fakeApi(user = paidUser())
        val credentials = FakeCredentials()
        credentials.saveFails = true

        val client = client(api, credentials)
        assertEquals(
            WaniKaniConnection.Unavailable(WaniKaniFailure.STORAGE),
            client.connect(TOKEN)
        )
        assertNull(credentials.read())
        assertNull(WaniKaniStore(dir).entitlement())
    }

    @Test
    fun `reconnecting without a token asks for one, and with one picks up the subscription`() = runBlocking {
        assertEquals(WaniKaniConnection.InvalidToken, client(fakeApi(), FakeCredentials()).reconnect())

        val api = fakeApi(user = freeUser())
        assertEquals(WaniKaniConnection.Connected(3), client(api, FakeCredentials(TOKEN)).reconnect())
    }

    @Test
    fun `disconnecting clears the token and the cache`() = runBlocking {
        val api = fakeApi(user = paidUser(), kanji = WaniKaniJson.collection(WaniKaniJson.kanji()))
        val credentials = FakeCredentials()
        val client = client(api, credentials)
        client.connect(TOKEN)
        client.lookup(WaniKaniTarget.Kanji("一"), false)
        assertTrue(WaniKaniStore(dir).subject(440) != null)

        assertTrue(client.disconnect())
        assertNull(credentials.read())
        assertNull(WaniKaniStore(dir).subject(440))
        assertEquals(WaniKaniLookup.NeedsToken, client.lookup(WaniKaniTarget.Kanji("一"), false))
    }

    @Test
    fun `a subject that could not be cached is shown, and says so`() = runBlocking {
        // The cache root is a file, so no entry can be written under it.
        File(folder.root, "wanikani").writeText("not a directory")
        val api = fakeApi(kanji = WaniKaniJson.collection(WaniKaniJson.kanji()))

        val available = client(api).lookup(WaniKaniTarget.Kanji("一"), false) as WaniKaniLookup.Available
        assertEquals(WaniKaniFailure.STORAGE, available.cacheFailure)
        assertEquals("一", (available.subject as WaniKaniSubject.Kanji).character)
    }

    private fun paidUser() = WaniKaniJson.user(type = "lifetime", maxLevelGranted = 60, periodEndsAt = null)

    private fun freeUser() = WaniKaniJson.user(type = "free", maxLevelGranted = 3, periodEndsAt = null)

    /**
     * An API answering the requests the client makes, from the payloads given here: `GET /user`, the
     * kanji a character resolves to, the components of that kanji, and a subject by id. Anything a
     * test does not supply is answered with [failure] rather than silently.
     */
    private fun fakeApi(
        user: String? = null,
        kanji: String? = null,
        components: String? = null,
        subject: String? = null,
        failure: WaniKaniFailure = WaniKaniFailure.OFFLINE
    ) = FakeWaniKaniApi { path, query ->
        when {
            path == "user" && user != null -> WaniKaniFetch.Body(user)
            path == "subjects" && query.any { it.first == "slugs" } && kanji != null -> WaniKaniFetch.Body(kanji)
            path == "subjects" && query.any { it.first == "ids" } && components != null -> WaniKaniFetch.Body(components)
            subject != null && path.startsWith("subjects/") -> WaniKaniFetch.Body(subject)
            else -> WaniKaniFetch.Failed(failure)
        }
    }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef"
        const val EXPIRY = "2018-12-11T13:32:19.485748Z"
        const val SVG_GROUND = "<svg xmlns=\"http://www.w3.org/2000/svg\">ground</svg>"
    }
}

/** Answers the requests the client makes, from the payloads a test hands it. */
private class FakeWaniKaniApi(
    var handler: (String, List<Pair<String, String>>) -> WaniKaniFetch = { _, _ ->
        WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE)
    }
) : WaniKaniApi() {

    val requests = mutableListOf<String>()
    val imageRequests = mutableListOf<String>()

    var imageHandler: (String) -> WaniKaniFetch = { WaniKaniFetch.Failed(WaniKaniFailure.OFFLINE) }

    override suspend fun get(path: String, query: List<Pair<String, String>>, token: String): WaniKaniFetch {
        requests += if (query.isEmpty()) {
            path
        } else {
            path + query.joinToString(separator = "&", prefix = "?") { "${it.first}=${it.second}" }
        }
        return handler(path, query)
    }

    override suspend fun getImage(url: String): WaniKaniFetch {
        imageRequests += url
        return imageHandler(url)
    }
}

/** The token store, without a keystore: what `connect`, `reconnect` and `disconnect` act on. */
private class FakeCredentials(private var stored: String? = null) : WaniKaniCredentials {

    var saveFails = false

    override fun hasToken(): Boolean = stored != null

    override fun read(): String? = stored

    override fun save(token: String): Boolean {
        if (saveFails) return false
        stored = token
        return true
    }

    override fun clear(): Boolean {
        stored = null
        return true
    }
}
