package com.personal.novellibrary

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import androidx.work.WorkManager
import com.personal.novellibrary.data.MIGRATION_1_2
import com.personal.novellibrary.data.MIGRATION_2_3
import com.personal.novellibrary.data.MIGRATION_3_4
import com.personal.novellibrary.backup.LibraryBackupManager
import com.personal.novellibrary.backup.RestoreMode
import com.personal.novellibrary.data.Genre
import com.personal.novellibrary.data.NovelDatabase
import com.personal.novellibrary.data.PlatformType
import com.personal.novellibrary.data.PlatformListingEntity
import com.personal.novellibrary.data.ReadingStatus
import com.personal.novellibrary.domain.RecommendationEngine
import com.personal.novellibrary.domain.LibraryFilter
import com.personal.novellibrary.domain.LibraryFilterEngine
import com.personal.novellibrary.domain.RecommendationRule
import com.personal.novellibrary.diagnostics.DiagnosticsService
import com.personal.novellibrary.diagnostics.PrdCoverage
import com.personal.novellibrary.repository.LibraryRepository
import com.personal.novellibrary.scanner.ScanSummary
import org.json.JSONObject
import com.personal.novellibrary.scanner.TxtFileScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.personal.novellibrary.worker.PlatformSearchWorker
import com.personal.novellibrary.settings.LibrarySettings
import com.personal.novellibrary.settings.LibrarySettingsStore

class LibraryApp : Application()

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val db = Room.databaseBuilder(app, NovelDatabase::class.java, NovelDatabase.NAME)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .build()
    private val repository = LibraryRepository(db.novelDao())
    private val settingsStore = LibrarySettingsStore(app)
    private val query = MutableStateFlow("")
    private val _scanSummary = MutableStateFlow<ScanSummary?>(null)
    private val _operationMessage = MutableStateFlow<String?>(null)
    private val _diagnostics = MutableStateFlow<String?>(null)
    private val _userTags = MutableStateFlow<Map<Long, List<String>>>(emptyMap())
    private val _platformListings = MutableStateFlow<Map<Long, List<PlatformListingEntity>>>(emptyMap())
    private val _busyOperation = MutableStateFlow<String?>(null)
    private val _scanDiscovered = MutableStateFlow(0)

    val novels = query.flatMapLatest { repository.observeNovels(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trash = repository.observeTrash()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val collections = repository.observeCollections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val candidates = repository.observeSearchCandidates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val syncJobs = repository.observeSyncJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val count = repository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val scanSummary = _scanSummary
    val operationMessage = _operationMessage
    val diagnostics = _diagnostics
    val userTags = _userTags
    val platformListings = _platformListings
    val busyOperation = _busyOperation
    val scanDiscovered = _scanDiscovered
    val settings = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibrarySettings())

    fun search(q: String) {
        query.value = q.trim()
    }

    fun scan(uri: Uri) {
        viewModelScope.launch {
            _busyOperation.value = "TXT 폴더 스캔"
            _scanDiscovered.value = 0
            _operationMessage.value = "TXT 폴더 스캔 중…"
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                settingsStore.saveLibraryTree(uri, settings.value.includeSubfolders)
                withContext(Dispatchers.IO) {
                    TxtFileScanner(getApplication(), db.novelDao())
                        .scanTree(uri, includeSubfolders = settings.value.includeSubfolders) {
                            _scanDiscovered.value = it
                        }
                }
            }.onSuccess {
                _scanSummary.value = it
                _operationMessage.value = "TXT ${it.discovered}개 스캔 완료"
            }.onFailure {
                _operationMessage.value = "스캔 실패: ${it.message ?: "폴더 접근 권한을 확인하세요."}"
            }
            _busyOperation.value = null
        }
    }

    fun setIncludeSubfolders(enabled: Boolean) = viewModelScope.launch { settingsStore.setIncludeSubfolders(enabled) }
    fun setWifiOnlySearch(enabled: Boolean) = viewModelScope.launch { settingsStore.setWifiOnlyBulkSearch(enabled) }
    fun setCollectReviews(enabled: Boolean) = viewModelScope.launch { settingsStore.setCollectReviews(enabled) }
    fun setPlatformEnabled(platform: PlatformType, enabled: Boolean) = viewModelScope.launch {
        settingsStore.setPlatformEnabled(platform, enabled)
    }

    fun refreshDiagnostics() = viewModelScope.launch {
        val service = DiagnosticsService(getApplication(), db.novelDao())
        _diagnostics.value = service.format(service.snapshot(dbVersion = 4))
    }

    fun loadUserTags(novelId: Long) = viewModelScope.launch {
        _userTags.value = _userTags.value + (novelId to repository.userTags(novelId))
    }

    fun loadPlatformListings(novelId: Long) = viewModelScope.launch {
        _platformListings.value = _platformListings.value + (novelId to repository.platformListings(novelId))
    }

    fun addUserTag(novelId: Long, name: String) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        repository.addUserTag(novelId, name)
        loadUserTags(novelId)
    }

    fun removeUserTag(novelId: Long, name: String) = viewModelScope.launch {
        repository.removeUserTag(novelId, name)
        loadUserTags(novelId)
    }

    fun favorite(id: Long, value: Boolean) = viewModelScope.launch {
        repository.favorite(listOf(id), value)
    }

    fun status(id: Long, status: ReadingStatus) = viewModelScope.launch {
        repository.readingStatus(listOf(id), status)
    }

    fun memo(id: Long, memo: String?) = viewModelScope.launch {
        repository.memo(id, memo?.takeIf { it.isNotBlank() })
    }

    fun rating(id: Long, rating: Float?) = viewModelScope.launch {
        repository.personalRating(id, rating)
    }

    fun infoLock(id: Long, locked: Boolean) = viewModelScope.launch {
        repository.infoLock(id, locked)
    }

    fun updateManualMetadata(id: Long, title: String, author: String, synopsis: String, genre: Genre) = viewModelScope.launch {
        _operationMessage.value = runCatching {
            repository.updateManualMetadata(id, title, author, synopsis, genre)
            "직접 입력 정보를 저장하고 잠갔습니다."
        }.getOrElse { "저장 실패: ${it.message}" }
    }

    fun bulkFavorite(ids: Set<Long>, favorite: Boolean) = viewModelScope.launch {
        if (ids.isNotEmpty()) repository.favorite(ids.toList(), favorite)
    }

    fun bulkStatus(ids: Set<Long>, status: ReadingStatus) = viewModelScope.launch {
        if (ids.isNotEmpty()) repository.readingStatus(ids.toList(), status)
    }

    fun bulkTrash(ids: Set<Long>) = viewModelScope.launch {
        if (ids.isNotEmpty()) repository.trash(ids.toList())
    }

    fun addSelectedToCollection(ids: Set<Long>, collectionName: String) = viewModelScope.launch {
        if (ids.isNotEmpty()) repository.addToCollection(ids.toList(), collectionName)
    }

    fun createFavoriteUnreadSmartCollection(name: String = "즐겨찾기 + 읽기 전") = viewModelScope.launch {
        repository.createSmartCollection(
            name = name,
            filterJson = JSONObject()
                .put("favoriteOnly", true)
                .put("unreadOnly", true)
                .toString(),
        )
    }

    fun recommendToday(): Long? = RecommendationEngine.pick(
        novels = novels.value,
        fileAvailability = emptyMap(),
        rule = RecommendationRule(unreadOnly = true, requireFileAvailable = false),
    )?.id

    fun exportBackup(uri: Uri) = viewModelScope.launch {
        _busyOperation.value = "백업 ZIP 생성"
        _operationMessage.value = runCatching {
            withContext(Dispatchers.IO) { LibraryBackupManager(getApplication()).exportBackup(uri, novels.value) }
            "백업 저장 완료"
        }.getOrElse { "백업 실패: ${it.message ?: "알 수 없는 오류"}" }
        _busyOperation.value = null
    }

    fun exportCsv(uri: Uri) = viewModelScope.launch {
        _busyOperation.value = "CSV 내보내기"
        _operationMessage.value = runCatching {
            withContext(Dispatchers.IO) { LibraryBackupManager(getApplication()).exportCsv(uri, novels.value) }
            "CSV 저장 완료"
        }.getOrElse { "CSV 저장 실패: ${it.message ?: "알 수 없는 오류"}" }
        _busyOperation.value = null
    }

    fun restoreBackup(uri: Uri, mode: RestoreMode = RestoreMode.MERGE) = viewModelScope.launch {
        _busyOperation.value = "백업 복원"
        _operationMessage.value = "백업 확인 중…"
        _operationMessage.value = runCatching {
            val payload = withContext(Dispatchers.IO) { LibraryBackupManager(getApplication()).readBackup(uri) }
            val count = repository.restoreNovels(payload.novels, mode)
            "백업에서 작품 ${count}개를 복원했습니다."
        }.getOrElse { "복원 실패: ${it.message ?: "알 수 없는 오류"}" }
        _busyOperation.value = null
    }

    fun openTxt(novelId: Long) = viewModelScope.launch {
        val uri = repository.availableFileUri(novelId)?.let(Uri::parse)
        if (uri == null) {
            _operationMessage.value = "열 수 있는 TXT 파일이 없습니다."
            return@launch
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure { _operationMessage.value = "TXT를 열 앱을 찾지 못했습니다." }
    }

    fun startPlatformSearch(ids: Set<Long>, forceRefresh: Boolean = false) {
        val targets = novels.value.filter { it.id in ids }
        targets.forEach { novel ->
            PlatformSearchWorker.enqueue(
                context = getApplication(),
                novelId = novel.id,
                query = novel.confirmedTitle ?: novel.normalizedTitle.ifBlank { novel.displayTitle },
                enabledPlatforms = settings.value.enabledPlatforms,
                wifiOnly = settings.value.wifiOnlyBulkSearch,
                forceRefresh = forceRefresh,
            )
        }
    }

    fun startPlatformSearch(novelId: Long, customQuery: String, forceRefresh: Boolean = false) {
        val novel = novels.value.firstOrNull { it.id == novelId } ?: return
        val searchQuery = customQuery.trim().ifBlank {
            novel.confirmedTitle ?: novel.normalizedTitle.ifBlank { novel.displayTitle }
        }
        PlatformSearchWorker.enqueue(
            context = getApplication(), novelId = novelId, query = searchQuery,
            enabledPlatforms = settings.value.enabledPlatforms,
            wifiOnly = settings.value.wifiOnlyBulkSearch, forceRefresh = forceRefresh,
        )
        _operationMessage.value = "‘$searchQuery’ 플랫폼 검색을 예약했습니다."
    }

    fun openPlatformUrl(url: String?) {
        val uri = url?.takeIf { it.startsWith("https://") }?.let(Uri::parse) ?: return
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure { _operationMessage.value = "플랫폼 페이지를 열 수 없습니다." }
    }

    fun cancelPlatformSearch(ids: Set<Long>) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        ids.forEach { WorkManager.getInstance(getApplication()).cancelAllWorkByTag("novel-$it") }
        repository.markSearchCancelled(ids.toList())
        _operationMessage.value = "플랫폼 검색 ${ids.size}개 취소 요청"
    }

    fun acceptCandidate(candidateId: Long) = viewModelScope.launch {
        candidates.value.firstOrNull { it.id == candidateId }?.let {
            repository.acceptCandidate(it)
            loadPlatformListings(it.novelId)
        }
    }

    fun excludeCandidate(candidateId: Long) = viewModelScope.launch {
        candidates.value.firstOrNull { it.id == candidateId }?.let { repository.excludeCandidate(it) }
    }

    fun restore(id: Long) = viewModelScope.launch {
        repository.restore(listOf(id))
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { NovelLibraryApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelLibraryApp(vm: LibraryViewModel = viewModel()) {
    val novels by vm.novels.collectAsState()
    val trash by vm.trash.collectAsState()
    val collections by vm.collections.collectAsState()
    val candidates by vm.candidates.collectAsState()
    val syncJobs by vm.syncJobs.collectAsState()
    val count by vm.count.collectAsState()
    val scanSummary by vm.scanSummary.collectAsState()
    val operationMessage by vm.operationMessage.collectAsState()
    val diagnostics by vm.diagnostics.collectAsState()
    val settings by vm.settings.collectAsState()
    val userTags by vm.userTags.collectAsState()
    val platformListings by vm.platformListings.collectAsState()
    val busyOperation by vm.busyOperation.collectAsState()
    val scanDiscovered by vm.scanDiscovered.collectAsState()
    var q by remember { mutableStateOf("") }
    var expandedNovelId by remember { mutableStateOf<Long?>(null) }
    var selectedNovelIds by remember { mutableStateOf(setOf<Long>()) }
    var showTrash by remember { mutableStateOf(false) }
    var collectionName by remember { mutableStateOf("") }
    var recommendedNovelId by remember { mutableStateOf<Long?>(null) }
    var genreFilter by remember { mutableStateOf<Genre?>(null) }
    var favoriteOnly by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::scan)
    }
    val backupExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(vm::exportBackup)
    }
    val csvExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::exportCsv)
    }
    val backupImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.restoreBackup(it, RestoreMode.MERGE) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Novel Library") }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Text("전체 작품 $count · 로컬 DB 우선 TXT 라이브러리")
            Text("기능 골격 ${PrdCoverage.overallPercent}% · 상용 준비도 ${PrdCoverage.commercialReadinessPercent}%")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showTrash = false }) { Text("라이브러리") }
                Button(onClick = { showTrash = true; selectedNovelIds = emptySet() }) { Text("휴지통 ${trash.size}") }
            }
            if (collections.isNotEmpty()) {
                Text("컬렉션 ${collections.size}개: ${collections.take(3).joinToString { it.name }}")
            }
            val pendingCandidateCount = candidates.count { it.status.name == "NEEDS_USER_CONFIRMATION" }
            if (pendingCandidateCount > 0) {
                Text("확인 필요한 후보 $pendingCandidateCount개")
            }
            if (syncJobs.isNotEmpty()) {
                val running = syncJobs.count { it.status.name == "RUNNING" || it.status.name == "QUEUED" }
                val success = syncJobs.count { it.status.name == "SUCCESS" }
                val failed = syncJobs.count { it.status.name == "FAILED" }
                Text("플랫폼 작업: 진행 $running · 성공 $success · 실패 $failed")
            }
            recommendedNovelId?.let { id ->
                Text("오늘의 추천 작품 ID: $id")
            }
            scanSummary?.let {
                Text("마지막 스캔: 신규 ${it.inserted}, 변경 ${it.changed}, 동일 ${it.unchanged}, 발견 ${it.discovered}")
            }
            operationMessage?.let { Text(it) }
            busyOperation?.let { operation ->
                Text("$operation 진행 중${if (operation.startsWith("TXT")) " · 발견 $scanDiscovered 개" else ""}")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            val recentJobs = syncJobs.filter { System.currentTimeMillis() - it.createdAt < 10 * 60 * 1000 }
            val activeJobs = recentJobs.count { it.status.name == "QUEUED" || it.status.name == "RUNNING" }
            if (activeJobs > 0) {
                val completedJobs = recentJobs.size - activeJobs
                val fraction = if (recentJobs.isEmpty()) 0f else completedJobs.toFloat() / recentJobs.size
                Text("플랫폼 검색 진행 $completedJobs/${recentJobs.size}")
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(null) }) { Text("TXT 폴더 선택/스캔") }
                Button(onClick = { showDiagnostics = !showDiagnostics; vm.refreshDiagnostics() }) { Text("진단") }
                Button(onClick = { showSettings = !showSettings }) { Text("설정") }
                Button(onClick = { recommendedNovelId = vm.recommendToday() }) { Text("오늘 뭐 읽지?") }
                Button(onClick = { backupExporter.launch("novel-library-backup.zip") }) { Text("백업") }
                Button(onClick = { backupImporter.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("복원") }
                Button(onClick = { csvExporter.launch("novel-library.csv") }) { Text("CSV") }
                Button(onClick = { vm.createFavoriteUnreadSmartCollection() }) { Text("스마트 컬렉션") }
            }
            if (showSettings) {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("스캔 및 검색 설정", style = MaterialTheme.typography.titleMedium)
                        SettingSwitch("하위 폴더 포함", settings.includeSubfolders, vm::setIncludeSubfolders)
                        SettingSwitch("대량 검색 Wi-Fi 전용", settings.wifiOnlyBulkSearch, vm::setWifiOnlySearch)
                        SettingSwitch("대표 리뷰 수집", settings.collectReviews, vm::setCollectReviews)
                        Text("통합 검색 플랫폼")
                        PlatformType.entries.forEach { platform ->
                            SettingSwitch(platform.name, platform in settings.enabledPlatforms) {
                                vm.setPlatformEnabled(platform, it)
                            }
                        }
                        Text("선택 폴더: ${settings.libraryTreeUri ?: "선택 전"}")
                    }
                }
            }
            if (showDiagnostics) {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("앱 상태 진단", style = MaterialTheme.typography.titleMedium)
                        Text(diagnostics ?: "진단 중…")
                        Row {
                            TextButton({ vm.refreshDiagnostics() }) { Text("새로고침") }
                            TextButton({
                                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Novel Library diagnostics", diagnostics.orEmpty()))
                            }) { Text("진단 결과 복사") }
                            TextButton({
                                context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                            }) { Text("권한 설정") }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { genreFilter = null; favoriteOnly = false }) { Text("전체") }
                Button(onClick = { genreFilter = Genre.ROMANCE_FANTASY }) { Text("로판") }
                Button(onClick = { genreFilter = Genre.BL }) { Text("BL") }
                Button(onClick = { favoriteOnly = !favoriteOnly }) { Text(if (favoriteOnly) "즐겨찾기 ON" else "즐겨찾기") }
            }
            OutlinedTextField(
                value = q,
                onValueChange = {
                    q = it
                    vm.search(it)
                },
                label = { Text("통합 검색") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (selectedNovelIds.isNotEmpty()) {
                Text("선택한 작품 ${selectedNovelIds.size}개")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { vm.bulkFavorite(selectedNovelIds, true) }) { Text("일괄 즐겨찾기") }
                    Button(onClick = { vm.bulkStatus(selectedNovelIds, ReadingStatus.PLAN_TO_READ) }) { Text("일괄 읽을 예정") }
                    Button(onClick = { vm.bulkTrash(selectedNovelIds); selectedNovelIds = emptySet() }) { Text("휴지통") }
                    Button(onClick = { vm.startPlatformSearch(selectedNovelIds) }) { Text("플랫폼 검색") }
                    Button(onClick = { vm.startPlatformSearch(selectedNovelIds, forceRefresh = true) }) { Text("강제 새로고침") }
                    Button(onClick = { vm.cancelPlatformSearch(selectedNovelIds) }) { Text("검색 취소") }
                    TextButton({ selectedNovelIds = emptySet() }) { Text("선택 해제") }
                }
                OutlinedTextField(
                    value = collectionName,
                    onValueChange = { collectionName = it },
                    label = { Text("컬렉션명") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { vm.addSelectedToCollection(selectedNovelIds, collectionName) }) { Text("컬렉션에 추가") }
                }
            }
            val visibleNovels = if (showTrash) trash else LibraryFilterEngine.apply(novels, LibraryFilter(genre = genreFilter, favoriteOnly = favoriteOnly))
            LazyColumn {
                items(visibleNovels, key = { it.id }) { novel ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(novel.confirmedTitle ?: novel.displayTitle, style = MaterialTheme.typography.titleMedium)
                                Text(if (novel.isFavorite) "♥" else "♡")
                            }
                            Text("${novel.author ?: "작가 미확인"} · ${novel.mainGenre} · ${novel.readingStatus}")
                            novel.synopsis?.let { Text(it, maxLines = 3) }
                            Row {
                                if (showTrash) {
                                    TextButton({ vm.restore(novel.id) }) { Text("복원") }
                                } else {
                                    TextButton({
                                        selectedNovelIds = if (novel.id in selectedNovelIds) {
                                            selectedNovelIds - novel.id
                                        } else {
                                            selectedNovelIds + novel.id
                                        }
                                    }) { Text(if (novel.id in selectedNovelIds) "선택됨" else "선택") }
                                    TextButton({ vm.favorite(novel.id, !novel.isFavorite) }) { Text("즐겨찾기") }
                                    TextButton({ vm.status(novel.id, ReadingStatus.PLAN_TO_READ) }) { Text("읽을 예정") }
                                    TextButton({ vm.status(novel.id, ReadingStatus.READ) }) { Text("읽음") }
                                    TextButton({
                                        expandedNovelId = if (expandedNovelId == novel.id) null else novel.id
                                        if (expandedNovelId == novel.id) {
                                            vm.loadUserTags(novel.id)
                                            vm.loadPlatformListings(novel.id)
                                        }
                                    }) { Text("상세/편집") }
                                }
                            }
                            if (expandedNovelId == novel.id) {
                                val novelCandidates = candidates.filter { it.novelId == novel.id && it.status.name == "NEEDS_USER_CONFIRMATION" }
                                if (novelCandidates.isNotEmpty()) {
                                    Text("검색 후보")
                                    novelCandidates.take(3).forEach { candidate ->
                                        Text("${candidate.platformType}: ${candidate.candidateTitle} / ${candidate.candidateAuthor ?: "작가 미상"}")
                                        candidate.candidateSynopsis?.let { Text(it, maxLines = 3) }
                                        Text("매칭 점수 ${candidate.confidence}")
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton({ vm.acceptCandidate(candidate.id) }) { Text("이 작품") }
                                            TextButton({ vm.excludeCandidate(candidate.id) }) { Text("제외") }
                                            TextButton({ vm.openPlatformUrl(candidate.candidateWorkId) }) { Text("원문 확인") }
                                        }
                                    }
                                }
                                var memoDraft by remember(novel.id, novel.memo) { mutableStateOf(novel.memo.orEmpty()) }
                                var tagDraft by remember(novel.id) { mutableStateOf("") }
                                var platformQuery by remember(novel.id) {
                                    mutableStateOf(novel.confirmedTitle ?: novel.normalizedTitle.ifBlank { novel.displayTitle })
                                }
                                var manualTitle by remember(novel.id, novel.confirmedTitle, novel.displayTitle) {
                                    mutableStateOf(novel.confirmedTitle ?: novel.displayTitle)
                                }
                                var manualAuthor by remember(novel.id, novel.author) { mutableStateOf(novel.author.orEmpty()) }
                                var manualSynopsis by remember(novel.id, novel.synopsis) { mutableStateOf(novel.synopsis.orEmpty()) }
                                var manualGenre by remember(novel.id, novel.mainGenre) { mutableStateOf(novel.mainGenre) }
                                Text("개인 평점: ${novel.personalRating?.toString() ?: "미평가"} · 정보 잠금: ${if (novel.isInfoLocked) "ON" else "OFF"}")
                                Text("플랫폼 연결", style = MaterialTheme.typography.titleMedium)
                                platformListings[novel.id].orEmpty().forEach { listing ->
                                    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                        Column(Modifier.padding(8.dp)) {
                                            Text("${listing.platformType} · ${listing.lookupStatus} · 점수 ${listing.matchConfidence}")
                                            listing.platformTitle?.let { Text(it) }
                                            listing.platformAuthor?.let { Text("작가: $it") }
                                            listing.synopsis?.let { Text(it, maxLines = 4) }
                                            if (listing.errorMessage != null) Text("오류: ${listing.errorMessage}")
                                            if (listing.detailUrl != null) {
                                                TextButton({ vm.openPlatformUrl(listing.detailUrl) }) { Text("플랫폼 페이지 열기") }
                                            }
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = platformQuery,
                                    onValueChange = { platformQuery = it },
                                    label = { Text("플랫폼 검색어") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton({ vm.startPlatformSearch(novel.id, platformQuery) }) { Text("이 검색어로 검색") }
                                    TextButton({ vm.startPlatformSearch(novel.id, platformQuery, forceRefresh = true) }) { Text("강제 재검색") }
                                    TextButton({ vm.loadPlatformListings(novel.id) }) { Text("결과 새로고침") }
                                }
                                Text("직접 정보 입력", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(manualTitle, { manualTitle = it }, label = { Text("확정 제목") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(manualAuthor, { manualAuthor = it }, label = { Text("작가") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(manualSynopsis, { manualSynopsis = it }, label = { Text("줄거리") }, modifier = Modifier.fillMaxWidth())
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton({ manualGenre = Genre.ROMANCE_FANTASY }) { Text("로판") }
                                    TextButton({ manualGenre = Genre.BL }) { Text("BL") }
                                    TextButton({ manualGenre = Genre.FANTASY }) { Text("판타지") }
                                    Text("선택: $manualGenre")
                                }
                                TextButton({
                                    vm.updateManualMetadata(novel.id, manualTitle, manualAuthor, manualSynopsis, manualGenre)
                                }) { Text("직접 입력 저장 + 잠금") }
                                val tags = userTags[novel.id].orEmpty()
                                if (tags.isNotEmpty()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        tags.take(6).forEach { tag ->
                                            TextButton({ vm.removeUserTag(novel.id, tag) }) { Text("#$tag ×") }
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = tagDraft,
                                    onValueChange = { tagDraft = it },
                                    label = { Text("사용자 태그") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                TextButton({
                                    vm.addUserTag(novel.id, tagDraft)
                                    tagDraft = ""
                                }) { Text("태그 추가") }
                                OutlinedTextField(
                                    value = memoDraft,
                                    onValueChange = { memoDraft = it },
                                    label = { Text("메모") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton({ vm.openTxt(novel.id) }) { Text("TXT 열기") }
                                    TextButton({ vm.memo(novel.id, memoDraft) }) { Text("메모 저장") }
                                    TextButton({ vm.rating(novel.id, 5f) }) { Text("★5") }
                                    TextButton({ vm.rating(novel.id, null) }) { Text("평점 지우기") }
                                    Row {
                                        Text("잠금")
                                        Switch(checked = novel.isInfoLocked, onCheckedChange = { vm.infoLock(novel.id, it) })
                                    }
                                }
                                Text("읽기 기록: 마지막 회차 ${novel.lastReadChapter ?: "미입력"}, 재독 ${novel.rereadCount}회")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
