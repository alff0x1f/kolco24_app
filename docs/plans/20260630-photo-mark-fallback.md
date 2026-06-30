# Фото-отметка КП — fallback при проблемах с NFC (Phase 1)

План добавления фото-отметки как дополнительного фактора подтверждения взятия КП:
когда у телефона нет NFC, метку сорвали с КП или чип не читается. Обсуждено в
брейншторме 2026-06-30.

## Контекст и решения

Фича уже частично «зарезервирована» в коде — реализуем заготовленные слоты, а не
строим подсистему с нуля:

- `MarkEntity.method` уже принимает `"nfc"` / `"photo"`, есть пустая колонка
  `photoPath: String?`.
- `MarkKind.PHOTO` и `PhotoTileBody(mark)` уже есть в `MarksScreen.kt` (с TODO про
  Coil).
- DTO-контракт `MarkDto.method` уже знает `"photo"`, а сам файл фото по контракту
  идёт **отдельным multipart-эндпоинтом** (`docs/design/UPLOAD.md`).
- FAB «Фото» на вкладке «Отметки» когда-то был, но скрыт в `fd3066f` (был лишь
  заглушкой с пустым `onClick`).

Принятые в брейншторме решения:

- **Роль фото — гибрид**: засчитывается локально (как `complete=true`), но помечена
  `method="photo"` (по сути флаг «требует проверки») — сервер authoritative и может
  откатить.
- **Камера — встроенная CameraX** (свой экран: затвор, лента миниатюр, фонарик), а не
  системный Intent — нужно несколько снимков за сессию (ночь, плохо видно).
- **Хранение — JSON-список путей в существующей колонке `photoPath`** (TEXT, уже
  nullable) → **без Room-миграции в Phase 1**. Отдельная таблица/колонка `photoCount`
  отвергнуты как лишняя сложность: count тривиально берётся из распарсенного списка в
  чистом маппере.
- **«Фото после NFC»** (auto-attach): если самое свежее `complete`-взятие было в
  пределах **3 минут** — фото привязывается к тому же КП без вопросов (с возможностью
  сменить КП). Иначе — спрашиваем номер КП.
- **Standalone фото-отметка** (NFC недоступен): новая строка `method="photo"`,
  `present=[]`, `complete=true` — КП засчитывается, состав не утверждаем.
- **Галерея**: одна плитка на взятие + бейдж «📷×N» с количеством снимков.

## Границы Phase 1

В Phase 1 фото **снимаются, хранятся, считаются и показываются** локально — полностью
рабочий on-device flow. **Выгрузка на сервер (и метаданных, и файла) — Phase 2** (см.
ниже).

⚠️ **Важно (выявлено plan-review):** существующий drain (`MarkDao.unuploadedLocal`/
`unuploadedCloud`) выбирает **все** pending-строки **без фильтра по `method`**, а
`uploadAllPending()` дёргается из Launch B и foreground-сервиса. Значит обычная
photo-`MarkEntity` с `uploaded*=false` **уйдёт** в существующий `uploadMarks` сразу,
с пустыми `cpUid`/`cpCode` — что (а) ломает заявленную «полностью локальную» Phase 1 и
(б) рискует уронить весь батч в `400`, утащив валидные NFC-марки.

**Решение Phase 1:** добавить в drain-запросы `MarkDao` фильтр `WHERE method != 'photo'`
(только запрос, **миграция не нужна**). Так photo-марки копятся локально с `uploaded*=0`
и **не** выгружаются, пока в Phase 2 не появится файловый эндпоинт; тогда фильтр просто
снимается и метаданные+файл идут вместе. Phase 1 остаётся migration-free и шипится
независимо; реальная `MIGRATION_2_3` + `schemas/3.json` (флаги выгрузки файлов) — Phase 2.

---

## Шаги Phase 1

### Task 1: Зависимости (`gradle/libs.versions.toml` + `app/build.gradle.kts`)

- [x] Добавить CameraX: `androidx.camera:camera-core`, `camera-camera2`,
      `camera-lifecycle`, `camera-view` (одна версия, minSdk-21-safe).
- [x] Добавить Coil: `io.coil-kt:coil-compose` (рендер локального файла в плитке и
      лайтбоксе; ровно то, на что указывает TODO в `PhotoTileBody`).
- [x] Добавить `androidx.exifinterface:exifinterface` (перенос orientation при даунскейле
      — см. шаг 6, иначе портрет показывается боком).
- [x] Прогнать сборку, убедиться, что AGP 9 / KSP-пины не конфликтуют.

### Task 2: Манифест и пермишены

- [x] `AndroidManifest.xml`: `<uses-permission android:name="android.permission.CAMERA"/>`
      + `<uses-feature android:name="android.hardware.camera.any" android:required="false"/>`
      (как у NFC/GPS — приложение ставится и без камеры).

### Task 3: Чистая модель путей фото (data-слой, JVM-тестируемо)

- [ ] Завести чистый хелпер (рядом с `MarkEntity` или отдельным файлом
      `data/marks/PhotoPaths.kt`):
  - `encodePhotoPaths(paths: List<String>): String` — JSON-энкод (kotlinx, как
    существующие конвертеры).
  - `photoPaths(raw: String?): List<String>` — декод, `null`/пусто → `emptyList()`,
    мусор → `emptyList()` (никогда не бросает). **Валидация пути:** принимать только
    относительные `marks/<markId>/<uuid>.jpg`; отбрасывать absolute и любые с `..`
    (path traversal — `File(filesDir, relPath)` иначе может уйти за пределы `filesDir`).
- [ ] Хранить **относительные** пути (`marks/<markId>/<uuid>.jpg`), абсолют резолвится
      от `filesDir` **в composable/адаптере** (чистый `marksToTiles` не имеет `filesDir` —
      резолв только на месте `AsyncImage`/записи файла).
- [ ] Тест `PhotoPathsTest`: round-trip, пустые/битые входы, порядок.

### Task 4: Чистый роутер точки входа (`ui/marks` или `data/marks`)

- [ ] `decidePhotoTarget(marks: List<MarkEntity>, nowMs: Long): PhotoTarget` где
      `PhotoTarget = AttachTo(markId, cpNumber, checkpointId) | AskNumber`.
  - Самое свежее `complete`-взятие с `(trustedTakenAt ?: takenAt)` в пределах
    `PHOTO_ATTACH_WINDOW_MS = 180_000L` → `AttachTo`; иначе `AskNumber`.
- [ ] `resolvePhotoCheckpoint(number: Int, legend: List<CheckpointEntity>): CheckpointEntity?`
      — номер обязан быть в легенде, иначе `null` (как v1 «warning if not in legend»).
      **Запертые КП (`locked=true`, `cost=null`) НЕ блокируются** — это и есть основной
      сценарий «сорвали метку» (код не считан → КП не раскрыт). Решено в брейншторме:
      photo-отметка запертого КП **считается (гибрид)** — `complete=true`, `cost=cp.cost ?: 0`
      (0 пока заперт), в легенде сразу «взято» (`takenPoints`), после раскрытия live-cost
      (`MarkRepository.totalScore(costOf)`) подхватывает реальную стоимость. Сервер
      пересчитает.
- [ ] Тесты `PhotoTargetTest` / `ResolvePhotoCheckpointTest`: внутри/вне окна, пустой
      список, неизвестный номер, граница ровно 3 мин, **запертый КП резолвится (не null)**.

### Task 5: `MarkRepository` / `MarkDao` — операции + фильтр drain

- [ ] **`MarkDao`: добавить `AND method != 'photo'` в drain И в счётчики/scope**
      (только запросы, без миграции) — чтобы photo-марки не уходили в `uploadMarks` в
      Phase 1 (см. «Границы Phase 1»). В Phase 2 фильтр снимается. Затронуть **все четыре**:
  - `unuploadedLocal` / `unuploadedCloud` (drain);
  - `uploadCounts` (иначе UI upload-status показывает photo-марки как вечно pending,
    т.к. local/cloud у них никогда не станут 1);
  - `pendingUploadScopes` (иначе scope с photo-маркой вечно в выборке → бесконечные
    холостые flush'и).

- [ ] `createPhotoMark(markId, cp, raceId, teamId, paths, sample: TimeSample)`
      — **`markId` приходит снаружи** (не минтится внутри): его генерит точка входа
      ещё до камеры, чтобы кадры писались в `marks/<markId>/` под тем же id, что и строка
      (см. шаг 6 — chicken-and-egg). Новая `MarkEntity`: `method="photo"`, `cpUid=""`,
      `cpCode=""`, `present=emptyList()`, `presentDetails=null`, `cost = cp.cost ?: 0`
      (⚠️ `CheckpointEntity.cost: Int?` — null у запертого КП, а `MarkEntity.cost` non-null),
      `expectedCount` = размер ростера (для лога), `complete=true`,
      `photoPath=encodePhotoPaths(paths)`, времена из `TimeSample`. На `applicationScope`.
- [ ] **Момент `TimeSample` — первый сохранённый кадр**, не FAB-click и не «Готово»
      (NFC семплирует время в момент физического тапа до I/O; для фото лучший прокси
      присутствия на КП — первый удавшийся снимок). Семплировать `trustedClock.sample()`
      в коллбэке первого `takePicture`-успеха и пронести в `createPhotoMark`.
- [ ] **Античит-координата (как у NFC-взятия):** на новой standalone photo-марке
      запустить одноразовый `currentLocationProvider.current()` → `attachLocation(markId, fix)`
      (`null` = no-op), тем же column-scoped путём, что NFC-flow на new-take ветке. Делаем
      в Phase 1, хотя выгрузка фото — Phase 2: координата важна как античит-сигнал
      (`docs/design/UPLOAD.md`), пишется в уже существующие `loc*` колонки (без миграции).
      Для `AttachTo` к NFC-строке координата уже есть — повторно не трогаем.
- [ ] `attachPhotos(markId, newPaths)` — ⚠️ **column-scoped, НЕ `upsert(mark.copy(...))`**.
      Полный read-modify-write строки затёр бы параллельные `present`/`complete`/`loc*`
      апдейты (`addMember`/`attachLocation` бегут fire-and-forget) — ровно поэтому
      `attachLocation` сделан column-scoped UPDATE. Реализовать как `@Transaction` в DAO:
      прочитать текущий `photoPath`, склеить JSON, затем
      `UPDATE marks SET photoPath = :new, updatedAt = :t WHERE id = :id` — трогаем **только**
      эти две колонки.
      ⚠️ **Флаги `uploaded*` НЕ сбрасывать** (явное намерение, не забывчивость): attach
      идёт к уже выгруженной NFC-строке, но `photoPath` **не входит в `MarkDto`** — значит
      перевыгружать marks-метаданные незачем, requeue был бы холостым POST. Сам файл фото
      в Phase 1 не выгружается вовсе. В Phase 2 для файлов появятся **отдельные**
      `photosUploaded*` (со своим backfill уже накопленных `photoPath`), а не reset общих
      `uploaded*`.
- [ ] Тесты в `MarkRepositoryTest`: создание photo-mark считается в
      `takenPointCount`/`totalScore` (distinct `complete` КП), attach дописывает пути и
      не плодит строк.
- [ ] `MarkRepositoryUploadTest`: photo-mark (`method="photo"`, `uploaded*=0`) **не**
      попадает в drain-выборку, а NFC-марка в том же наборе — попадает. ⚠️ Тут fake-DAO
      сам реализует фильтр в Kotlin → это проверяет **контракт репозитория**, а не реальный
      SQL. Фильтр живёт в `@Query`, поэтому добавить ещё **инструментальный `MarkDaoTest`**
      (Room in-memory), проверяющий, что настоящие `unuploadedLocal/Cloud`/`uploadCounts`/
      `pendingUploadScopes` исключают `method='photo'`. Fake обновить как контракт.

### Task 6: Камера (`ui/photo/PhotoCaptureScreen.kt`) — оверлей

- [ ] Полноэкранный оверлей по паттерну `rememberSaveable`-флага + `BackHandler` после
      `Scaffold` (как scan/bind). Вход: `PhotoTarget` (КП + `markId`). **`markId`
      определён до камеры** для обоих путей: `AttachTo` несёт существующий id; для
      `AskNumber` точка входа минтит новый UUID заранее и передаёт его и в путь файла, и в
      `createPhotoMark` (фикс chicken-and-egg).
- [ ] CameraX: `PreviewView` через `AndroidView`, `ImageCapture`. Кнопка затвора,
      тоггл фонарика (`cameraControl.enableTorch` — ночной affordance), горизонтальная
      лента миниатюр уже снятого, кнопка «Готово (N)».
- [ ] Каждый затвор → `ImageCapture.takePicture` → даунскейл (≤1600px, JPEG ~80%) →
      запись `filesDir/marks/<markId>/<uuid>.jpg` на `Dispatchers.IO` → добавить в ленту.
- [ ] **Orientation:** при даунскейле через декод/re-`compress` EXIF теряется → портрет
      боком (Coil читает EXIF, у re-encoded bitmap его нет). Применять поворот к bitmap до
      энкода **или** копировать orientation на выход (`androidx.exifinterface`, API 24+).
      Проще всего — повернуть bitmap по `imageInfo.rotationDegrees` из `ImageCapture`.
- [ ] Удаление кадра из ленты до коммита (✕/long-press) — смазанный ночной кадр
      выкидываем; **физически удалять файл с диска** (не только из ленты).
- [ ] «Готово» на `applicationScope`: `AttachTo` → `attachPhotos`; `AskNumber` →
      `createPhotoMark`. Back с 0 кадров — no-op; с кадрами — confirm перед сбросом;
      **при сбросе удалить уже записанные файлы** этой сессии (иначе orphan на диске).
- [ ] **State-restore (`rememberSaveable`):** `PhotoTarget` несёт `markId`/`cpNumber`/
      `checkpointId` — обычный data class **не сохраняется** (существующие saveable-стейты
      в `MainActivity` — примитивы/enum/int). Сделать `PhotoTarget` `@Parcelize`
      (Parcelable) **или** разложить на saveable-примитивы (`markId: String?`,
      `cpNumber: Int`, `checkpointId: Int`, mode-флаг). Ленту уже снятого тоже хранить
      `rememberSaveable` как `ArrayList<String>` — иначе поворот экрана теряет кадры.
- [ ] **Cleanup orphan-файлов (3 источника):**
  - сброс сессии (back-с-кадрами/✕) — удалить файлы кадров;
  - debug-`AppContainer.clearDatabase()` (кнопка «Очистить базу данных») — чистить
    `filesDir/marks/` (иначе фото-orphan'ы после wipe БД, а фичи удаления марок нет);
  - **startup-sweep** (process death посреди незакоммиченной standalone-сессии: каталог
    `marks/<id>/` есть, а строки `MarkEntity` ещё нет): на старте приложения пройти
    подкаталоги `filesDir/marks/` и удалить те, чей `<id>` не существует как `MarkEntity.id`
    (каталог именован markId) — детерминированно реклеймит все orphan-случаи. Потеря кадров
    при kill посреди съёмки приемлема (best-effort, как у трека).
- [ ] CAMERA-пермишен запрашивается при открытии; отказ → rationale и закрытие.
- [ ] (Опц.) `scanFeedback.success()` на затвор — переиспользуем плеер, без нового звука.
- [ ] Адаптер CameraX и сам composable — **без тестов по конвенции** (Android-адаптер);
      даунскейл-параметры/чистые куски — тестируемы.

### Task 7: Пикер номера КП (`ui/photo/PhotoNumberPicker.kt`) — оверлей

- [ ] Лёгкий оверлей (`rememberSaveable`-флаг + `BackHandler`): числовое поле + список
      легенды с фильтром по вводу (переиспользуем легенду, уже собранную в
      `MainActivity`). Невалидный номер → инлайн-ошибка, без orphan-марок.
- [ ] Выбор КП → переход в `PhotoCaptureScreen` с `AskNumber`-таргетом.

### Task 8: Точка входа — FAB на «Отметки» (`MarksScreen.kt` + `MainActivity.kt`)

- [ ] Вернуть скрытый FAB «Фото» (иконка `CameraAlt`, `OrangeCta`). NB: scan-FAB **нет**
      — скан открывается автоматически по NFC-тапу; это новый и единственный FAB.
- [ ] FAB гейтится на `SelectedTeamState`: нет команды (`None`/`Missing`) → пикер команды.
- [ ] `onClick` → `decidePhotoTarget(marks, trustedNow)`:
  - `AttachTo` → сразу открыть камеру, в шапке «КП №N» + «изменить» (drop в пикер).
  - `AskNumber` → открыть `PhotoNumberPicker`.
- [ ] Развести флаги оверлеев `showPhotoPicker`/`photoCapture` в `MainActivity` по
      существующему паттерну (сброс на `onScanClick`/смене команды).
- [ ] **Взаимная блокировка оверлеев (детально, не «развести флаги»):** в `MainActivity`
      много guard'ов, где scan/settings/admin/team-picker/bind взаимоисключают друг друга
      (см. busy-conditions и стек `BackHandler`'ов). Явно встроить photo picker и camera:
  - не открывать фото поверх другого оверлея (picker/settings/admin/scan/bind уже подняты);
  - **NFC-tap не должен открывать scan/idle-capture поверх камеры** — пока поднята
    камера/picker, idle-путь и `captured`-dispatch должны дропать (как при другом оверлее);
  - зарегистрировать `BackHandler`'ы камеры и picker в правильном порядке стекинга
    (глубже — раньше), каждый срабатывает только когда нет более глубокого оверлея.

### Task 9: Галерея — плитка + бейдж + лайтбокс (`MarksScreen.kt`)

- [ ] `Mark` (вью-модель плитки) получает `photoPaths: List<String>` (относительные) и
      производный `photoCount` (= `photoPaths.size`); `marksToTiles` заполняет из
      `photoPaths(m.photoPath)`. `kind` — без изменений (`method=="photo"` → `PHOTO`).
      ⚠️ Сейчас `Mark` несёт только `number/cost/kind/time/color` — путей нет, поэтому
      и плитка, и лайтбокс без этого поля рисовать нечем (TODO в `PhotoTileBody` прямо
      ждёт `mark.photoPath`).
- [ ] `PhotoTileBody`: заполнить первым фото — `AsyncImage` по `File(filesDir, relPath)`,
      резолв `filesDir` на месте через `LocalContext` (маппер пуст и не знает `filesDir`),
      сохранить нижний scrim-caption (номер/стоимость/время).
- [ ] Бейдж «📷×N» в углу для **любой** плитки с `photoCount > 0` (и NFC, и PHOTO) —
      бейдж завязан на `photoCount`, плитка-тип на `method` (разводка намеренная: NFC-
      взятие может нести фото-доказательство, оставаясь цветной плиткой).
- [ ] Тап по плитке с фото → лайтбокс: полноэкранный `HorizontalPager` по N снимкам
      (view-only в Phase 1). Плитка без фото — поведение как сейчас.
- [ ] Расширить `MarksMappingTest`: `photoCount` маппинг, бейдж-условие, photo-mark с
      пустым `present` и `complete=true` попадает в плитки.

### Task 10: Проверка

- [ ] `./gradlew lintDebug` — чисто (особенно `NewApi` вокруг CameraX/файлов).
- [ ] `./gradlew testDebugUnitTest` — новые чистые тесты зелёные.
- [ ] Ручной прогон: NFC-взятие → в течение 3 мин FAB «Фото» → auto-attach, бейдж ×N;
      по прошествии 3 мин → пикер номера → standalone-плитка; ночь → фонарик; несколько
      кадров → один тайл с count; back/discard; отказ в пермишене.

---

## Вне Phase 1 (Phase 2 — выгрузка)

Когда появится бэкенд-эндпоинт:

- Контракт `POST /app/race/<race_id>/mark-photo/` (multipart: подписанная JSON-часть
  `team_id`/`source_install_id`/`mark_id`/`photo_id` + JPEG; идемпотентность по
  `(mark_id, photo_id)`; sha256 по всему телу — поэтому даунскейл важен уже в Phase 1)
  в `docs/design/UPLOAD.md`.
- Две колонки-флага `photosUploadedLocal`/`photosUploadedCloud` + **реальная**
  `MIGRATION_2_3` + `schemas/3.json` (Room шипнут — иначе краш на апгрейде).
- Self-healing loop выгрузки файлов в стиле `Mutex.tryLock`/батчи дуально (cloud+LAN),
  как у marks/track; досылает уже накопленные локальные фото.
- Снять фильтр `method != 'photo'` из drain-запросов `MarkDao` (шаг 5) — теперь
  метаданные+файл идут вместе.
- Если для шеринга/`content://` понадобится FileProvider — добавить `files-path` запись
  в `res/xml/file_paths.xml` (сейчас там только `cache-path tracks/`). В Phase 1 не нужно
  (Coil читает `File` напрямую).

## Заметки/риски

- **`expected_count` у photo-mark**: ставим размер текущего ростера для серверного
  лога; на локальный скоринг не влияет (`complete=true` задаётся явно, не из
  `present.size`). Сервер всё равно пересчитает.
- **Память при будущем хешировании multipart**: даунскейл (≤1600px) в Phase 1 заранее
  держит файл хешируемым в Phase 2.
- **Смена `photoPath`-семантики не требует миграции** — тип колонки остаётся TEXT.
