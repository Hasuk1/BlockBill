# Проверенные API BlockBill (2026-10-27)

Цель проверки: Minecraft 26.3, Fabric API 0.165.0+26.3, Loader 0.20.1, Loom 1.19.3.
Проверка API означает чтение точных исходников/bytecode; результаты игровых запусков фиксируются отдельно.

## Происхождение доказательств

- [Fabric API POM 0.165.0+26.3](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.165.0+26.3/fabric-api-0.165.0+26.3.pom).
- [fabric-entity-events-v1 6.0.4+3434d6d95d sources](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-entity-events-v1/6.0.4+3434d6d95d/fabric-entity-events-v1-6.0.4+3434d6d95d-sources.jar).
- [fabric-gametest-api-v1 4.0.35+3434d6d95d sources](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-gametest-api-v1/4.0.35+3434d6d95d/fabric-gametest-api-v1-4.0.35+3434d6d95d-sources.jar).
- Minecraft common jar, загруженный Loom в `~/.gradle/caches/fabric-loom/26.3/minecraft-common.jar`; сигнатуры и порядок вызовов проверены `javap -c -p`.
- Loom 1.19.3 jar: `FabricApiTesting.getSourceSetName()` возвращает `gametest`; run configuration называется `gameTest`.
- Вспомогательные локальные выгрузки находятся в `.local/research/` и не входят в Git.

## Сон и точка возрождения

Используется `net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents.ALLOW_SLEEPING`:

```java
Player.BedSleepingProblem allowSleep(Player player, BlockPos sleepingPos);
```

`null` разрешает дальнейшее ванильное выполнение. Для отказа пригоден
`new Player.BedSleepingProblem(Component.translatable("blockbill.sleep.denied"))`.
В 26.3 `BedSleepingProblem` является record с публичным конструктором `Component`.
`AbstractBedBlock.useWithoutItem` показывает `problem.message()` через `sendOverlayMessage`.

Точное место Fabric hook: `PlayerMixin` инжектирует событие в начало
`Player.startSleepInBed(AbstractBedBlock, BlockState, BedRule, BlockPos)`.
У серверного переопределения перед вызовом родителя выполняются:

1. Проверки состояния игрока, дальности и препятствий.
2. Установка respawn, если `BedRule.canSetSpawn(level)` разрешает.
3. Проверки времени и монстров.
4. `super.startSleepInBed(...)`, где срабатывает событие.

В bytecode Minecraft 26.3 вызов `setRespawnPosition` расположен на offset 124,
вызов родительского `startSleepInBed` — на offset 266.
Следовательно, Sleep+ может отказать в сне, не блокируя ванильную установку точки
возрождения. Событие `ALLOW_SETTING_SPAWN` модом не изменяется.

`StrawBedBlock` наследуется от `AbstractBedBlock` и использует тот же путь сна.
[Официальные release notes 26.3](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)
описывают соломенную кровать как одноразовую, не устанавливающую spawn;
в Nether/End попытка её использования разрушает кровать. BlockBill сохраняет эти
ванильные правила. Разрушение/взрыв кровати в запрещённом измерении обрабатываются
ванилью до sleep event. Собственный Mixin или отмена `UseBlockCallback` не нужны.

Ограничение: событие вызывается после ванильных проверок, поэтому при препятствии,
монстрах или неподходящем времени показывается соответствующая ванильная причина.

## Сохранение

Проверенные сигнатуры 26.3:

```java
SavedDataType<T>(Identifier id, Supplier<T> constructor, Codec<T> codec, DataFixTypes dataFixType);
SavedDataStorage(Path directory, DataFixer dataFixer, HolderLookup.Provider registries);
<T extends SavedData> T SavedDataStorage.computeIfAbsent(SavedDataType<T> type);
<T extends SavedData> void SavedDataStorage.set(SavedDataType<T> type, T value);
CompletableFuture<?> SavedDataStorage.scheduleSave();
void SavedDataStorage.saveAndJoin();
void SavedDataStorage.close();
```

`ServerLevel.getDataStorage()` возвращает `SavedDataStorage`. Старое имя
`DimensionDataStorage` отсутствует в этой версии. `SavedDataType` принимает
`Identifier`, не строку. Для ключей UUID доступен `UUIDUtil.STRING_CODEC`.
Общую таблицу аккаунтов следует получать из Overworld, независимо от текущего
измерения игрока. Она не привязана к экземпляру player entity и переживает respawn.

`SavedData.setDirty()` изменяет только boolean. Синхронную запись он не запускает.
`SavedDataStorage` собирает dirty данные в NBT перед отложенной записью, затем
сбрасывает флаг. Данные помечаются после транзакций, периодически при изменении
часов и перед остановкой сервера.

[Руководство Saved Data](https://docs.fabricmc.net/develop/serialization/saved-data)
на дату исследования помечено 26.2; сигнатуры выше дополнительно проверены по jar 26.3.

## Серверные GameTests

Loom configuration:

```groovy
fabricApi {
    configureTests {
        createSourceSet = true
        modId = 'blockbill-test'
        enableGameTests = true
        enableClientGameTests = false
        eula = true
    }
}
```

`eula = true` принимает Minecraft EULA для тестового запуска. Тестовый mod descriptor
находится в `src/gametest/resources/fabric.mod.json`; entrypoint называется
`fabric-gametest`. Задача запуска: `./gradlew runGameTest`.
Тестовые классы не должны входить в основной release jar.

Аннотация `net.fabricmc.fabric.api.gametest.v1.GameTest` применяется к публичному
нестатическому методу `void method(GameTestHelper helper)`. По умолчанию используется
пустая структура 8×8; её не надо генерировать вручную.

`helper.makeMockServerPlayerInLevel()` создаёт `ServerPlayer`, `Connection`,
Netty `EmbeddedChannel` и вызывает `PlayerList.placeNewPlayer`, но его анонимный
подкласс `GameTestHelper$3` **всегда возвращает CREATIVE из gameMode()**. Вызов
`setGameMode(SURVIVAL)` этот override не меняет. Это подтверждено первым runtime
запуском и `javap` указанного класса. Поэтому BlockBill fixtures повторяют подключение
через `EmbeddedChannel` и `placeNewPlayer`, создавая обычный `ServerPlayer` с уникальными
UUID и именем. Его режим действительно изменяется, и online server tick events проверяют
survival логику. Дополнительно Connection добавляется в
`server.getConnection().getConnections()` и отправляется `ServerboundPlayerLoadedPacket`:
без регистрации в network tick loop не вызывается `ServerPlayer.doTick()` и у
синтетического игрока не уменьшается TTL ванильных эффектов. Это обнаружено вторым
runtime запуском (экономика/сон/часы прошли, истечение эффектов не прошло) и подтверждено
bytecode `ServerGamePacketListenerImpl.tickPlayer()`. `makeMockServerPlayer(GameType)`
online игрока не регистрирует.

Команды проходят через фактически зарегистрированный Brigadier dispatcher:
`server.getCommands().getDispatcher().execute(command, source)`.
Управление permissions в 26.3 выполняется через
`source.withPermission(PermissionSet.NO_PERMISSIONS / ALL_PERMISSIONS)`.

Покрытие `BlockBillGameTests`: смешанные брёвна и offhand, все exchange offers,
ошибки количества и overflow, атомарные покупки, trial и продление, UUID-изоляция,
survival/creative/spectator/adventure, отсутствие offline списаний, operator debug,
обычная и соломенная кровать/respawn, реальные эффекты Mine+/Sprint+, сохранение
чужого эффекта, смерть/respawn, смена измерения, повторное подключение с тем же UUID и NBT disk reload.

Проверка Sprint+ поддерживает server-side флаг намерения спринта каждый реальный тик;
это проверка адаптера и времени, а не эмуляция клавиатуры клиента. Проверка сохранения
создаёт отдельный временный `SavedDataStorage`, закрывает его и открывает заново с тем
же путём; это настоящий дисковый roundtrip, но не полный рестарт JVM/мира.

Источники: [Loom test DSL](https://docs.fabricmc.net/develop/loom/fabric-api#tests),
[Automated Testing](https://docs.fabricmc.net/develop/automatic-testing).

## Полный restart и загрузка release JAR

`gradle/verification.gradle` добавляет отдельный `verificationJar` из `gametest`
source set и `productionServerSmoke` типа `ServerProductionRunTask`. Точный bytecode
Loom 1.19.3 показывает: для unobfuscated Minecraft task автоматически добавляет
основной `jar`, а также `productionRuntimeMods`; каталоги dev class output вместо
JAR не подставляются. Installer 1.1.2 закреплён по
[официальной metadata](https://meta.fabricmc.net/v2/versions/installer).

Воспроизведение (последовательно):

```sh
./gradlew productionServerSmoke -PrestartPhase=write
./gradlew productionServerSmoke -PrestartPhase=read
```

Это два отдельных запуска JVM, общий мир `.local/production-smoke/blockbill-verification`.
Тестовый initializer `RestartSmoke` активен только при system property
`blockbill.restartPhase`, проверяет два фиксированных синтетических UUID, кредиты,
trial, активное время и три expiry. Фаза write использует обычный `Accounts` и
`SubscriptionEngine`; штатная остановка сервера сохраняет мир. Фаза read сравнивает
весь snapshot. Повторный write допускает только новый либо точно совпадающий fixture
и не сбрасывает произвольные аккаунты.

Успешные markers `.local/production-smoke/write-success.json` и `read-success.json`
пишутся только из `SERVER_STOPPED`, после штатного сохранения. В каждом записаны PID,
путь source JAR, SHA-256, путь мира и два snapshot. Сравнивать надо оба markers:
разные PID, одинаковый JAR SHA-256, одинаковый мир и данные. Один лишь Gradle exit 0
не заменяет наличие markers. `RestartSmoke` отвергает загрузку BlockBill из каталога
classes вместо JAR. Верификационный JAR не предназначен для установки пользователем.

Тестовый dedicated server слушает только `127.0.0.1:25576`, разрешает один слот;
online-mode отключён только в этом синтетическом runner. Реальные миры не используются.

[Официальная документация production tasks](https://docs.fabricmc.net/develop/loom/production-run-tasks).
