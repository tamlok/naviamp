package app.naviamp.domain.home

import app.naviamp.domain.InternetRadioStation
import app.naviamp.domain.cache.ProviderResponseService
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.settings.RecentRadioStream
import app.naviamp.domain.connect.NaviampConnectSourceIdentity
import app.naviamp.domain.radio.DefaultRadioMinimumTracks
import app.naviamp.domain.radio.GeneratedRadioRepository
import app.naviamp.domain.radio.MemoryGeneratedRadioRepository
import app.naviamp.domain.radio.RadioTuningSettings
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

data class HomeContentLoadRequest(
    val provider: MediaProvider,
    val providerResponseService: ProviderResponseService? = null,
    val libraryRepository: HomeLibraryRepository? = null,
    val sourceId: String? = null,
    val date: HomeDate,
    val recentRadioStreams: List<RecentRadioStream> = emptyList(),
    val recentInternetRadioStations: List<InternetRadioStation> = emptyList(),
    val artistLimit: Int = HomeDefaultArtistLimit,
    val forceRefreshRecentlyAdded: Boolean = false,
    val observedAtIso8601: () -> String = { "" },
    val generatedRadio: GeneratedRadioRepository = MemoryGeneratedRadioRepository(),
    val generatedRadioSourceIdentity: NaviampConnectSourceIdentity? = null,
    val forceRefreshMixes: Boolean = false,
    val nowMillis: () -> Long = { 0L },
    val random: Random = Random.Default,
    val sonicSimilarity: Boolean = false,
    val radioTuning: RadioTuningSettings = RadioTuningSettings(),
    val radioBatchSize: Int = DefaultRadioMinimumTracks,
    val isCurrent: () -> Boolean = { true },
)

class HomeContentCoordinator(
    private val setHomeContent: (HomeContent) -> Unit,
    private val setHomeStatus: (String?) -> Unit,
) {
    private var generation = 0L
    private var cachedScope: Triple<String?, String, NaviampConnectSourceIdentity?>? = null
    private var cachedContent: HomeContent? = null

    suspend fun load(
        request: HomeContentLoadRequest,
        loadContent: suspend (HomeContentLoadRequest) -> HomeContent = ::loadHomeContent,
    ) {
        val token = ++generation
        val scope = Triple(request.sourceId, request.provider.cacheNamespace, request.generatedRadioSourceIdentity)
        val currentRequest = request.copy(isCurrent = {
            token == generation && request.isCurrent() && request.provider.cacheNamespace == scope.second
        })
        setHomeStatus(HomeLoadingStatus)
        runCatching { loadContent(currentRequest) }
            .onSuccess { content ->
                if (!currentRequest.isCurrent()) return@onSuccess
                val retained = cachedContent?.takeIf { cachedScope == scope }?.let(content::retainFailedSections) ?: content
                cachedScope = scope
                cachedContent = retained
                setHomeContent(retained)
                setHomeStatus(null)
            }
            .onFailure { exception ->
                if (exception is CancellationException) throw exception
                if (currentRequest.isCurrent()) setHomeStatus(homeLoadFailureStatus(exception))
            }
    }
}

suspend fun loadHomeContent(request: HomeContentLoadRequest): HomeContent =
    HomeService(
        provider = request.provider,
        providerResponseService = request.providerResponseService,
        libraryRepository = request.libraryRepository,
        sourceId = request.sourceId,
        date = request.date,
        observedAtIso8601 = request.observedAtIso8601,
        generatedRadio = request.generatedRadio,
        generatedRadioSourceIdentity = request.generatedRadioSourceIdentity,
        nowMillis = request.nowMillis,
        random = request.random,
        sonicSimilarity = request.sonicSimilarity,
        radioTuning = request.radioTuning,
        radioBatchSize = request.radioBatchSize,
        isCurrent = request.isCurrent,
    ).load(
        recentRadioStreams = request.recentRadioStreams,
        recentInternetRadioStations = request.recentInternetRadioStations,
        artistLimit = request.artistLimit,
        forceRefreshRecentlyAdded = request.forceRefreshRecentlyAdded,
        forceRefreshMixes = request.forceRefreshMixes,
    )

fun homeLoadFailureStatus(exception: Throwable): String =
    exception.message ?: HomeLoadFailureFallbackStatus

const val HomeLoadingStatus = "Loading home..."
const val HomeLoadFailureFallbackStatus = "Could not load home."
