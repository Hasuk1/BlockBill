# API расширения BlockBill 0.2

Проверено по Minecraft 26.3 `minecraft-common.jar` (`javap -c -p`) и точному
[fabric-events-interaction-v0 5.3.9+b9b88df45d sources](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-events-interaction-v0/5.3.9+b9b88df45d/fabric-events-interaction-v0-5.3.9+b9b88df45d-sources.jar),
входящему в Fabric API 0.165.0+26.3. Свои Mixins не добавляются.

## Серверные точки перехвата

| Сервис | API | Место в Fabric implementation |
| --- | --- | --- |
| Enchant+, Anvil+, Smith+, Storage+ | `UseBlockCallback.EVENT` | HEAD `ServerPlayerGameMode.useItemOn(...)` |
| Trade+ | `UseEntityCallback.EVENT` | `ServerGamePacketListenerImpl.handleInteract(...)` до получения предмета и ванильного взаимодействия |
| Fish+ | `UseItemCallback.EVENT` | HEAD `ServerPlayerGameMode.useItem(...)` |

Все три callback возвращают `InteractionResult`. `PASS` продолжает ванильное действие,
`FAIL` на логическом сервере отменяет его до открытия меню, заброса или изменения
предмета. Проверяется `player.gameMode() == GameType.SURVIVAL`; ограничения отсутствуют
в creative, spectator и adventure. Кошелёк и доступ читаются только у `ServerPlayer`
из общего `Accounts` в Overworld. Дополнительного сканирования мира/чанков нет.

## Клиент и вторая рука

Клиент не имеет достоверного счёта. Для нового взаимодействия, соответствующего услуге,
callback возвращает `SUCCESS`: Fabric отправляет обычный interaction packet серверу,
а клиент завершает обработку на этой руке. Это предотвращает fallback к предмету или
второй руке при отказе сервера. Доступ на клиенте не решается, кредиты не меняются.

Для `UseItemCallback` выбран именно `SUCCESS`, поскольку в исходнике
`MultiPlayerGameModeMixin.interactItem` отправка `ServerboundUseItemPacket` при
не-PASS результате проверяет равенство `InteractionResult.SUCCESS`.
Использовать `CONSUME` здесь нельзя: оно не отправит пакет этим Fabric hook.
Для block callback достаточно consumesAction, но тот же `SUCCESS` работает единообразно.

Разрешённое действие выполняется ванилью на сервере. Клиентская предикция guarded
действия пропускается, поэтому косметический отклик может отличаться от vanilla
(в частности локальный звук заброса); авторитетные меню, предметы и сущности создаёт
сервер. Сматывание существующей лески и разрешённое sneak placement проходят как
обычные ванильные взаимодействия на обеих сторонах.

Документация: [UseBlockCallback](https://maven.fabricmc.net/docs/fabric-api-0.165.0+26.3/net/fabricmc/fabric/api/event/player/UseBlockCallback.html),
[UseItemCallback](https://maven.fabricmc.net/docs/fabric-api-0.165.0+26.3/net/fabricmc/fabric/api/event/player/UseItemCallback.html).

## Точное соответствие действий

- Стол зачарования: `EnchantingTableBlock`.
- Все три состояния наковальни: `AnvilBlock`.
- Кузнечный стол: `SmithingTableBlock`.
- Эндер-сундук: `EnderChestBlock`; все 16 окрашенных и неокрашенный шалкер:
  `ShulkerBoxBlock`. Обычные сундуки, бочки, copper chest, лодки/вагонетки с сундуками
  этим условием не затрагиваются.
- Приседание с непустой основной **или** второй рукой возвращает `PASS`. Это точное
  условие vanilla `isSecondaryUseActive && hasAnyItem` перед пропуском block use в
  `ServerPlayerGameMode.useItemOn`. Поэтому размещение блока около станции не требует
  её подписки; приседание с двумя пустыми руками не становится обходом оплаты.
- Trade+: живой взрослый `Villager` или `WanderingTrader`, который сейчас не спит и не
  торгует. `VILLAGER_SPAWN_EGG` исключён как в ванильном `mobInteract`. Zombie villagers,
  дети, животные и остальные сущности не попадают под правило; лечение и кормление
  доступны. После оплаты остаются ванильные ограничения (например, отсутствие сделок).
- Fish+: точный `Items.FISHING_ROD` и `player.fishing == null`. Ненулевой existing hook
  всегда пропускает callback, даже после истечения trial/платного срока. Это позволяет
  смотать уже заброшенную удочку, не удаляя и не восстанавливая item stack вручную.

Ограничивается только начало взаимодействия. Уже открытое меню не закрывается,
предметы из его слотов не изымаются. Разрушение/подбор контейнера не перехватываются.

## Сообщения

`blockbill.access.denied` получает три аргумента:
локализованное название услуги, индивидуальную цену, точную команду
`/blockbill buy <id>`. Повторные отказы ограничены одним сообщением за 20 активных
survival-тиков данного UUID; повторный отказ всё равно отменяет действие.
Память cooldown очищается на disconnect и stop. Время не берётся из системных часов
или изменяемого `/time` времени суток.

## Серверные проверки

`ExpansionGameTests` включает восемь сценариев:

1. Реальное открытие workshop menu до/после индивидуальной покупки, все наковальни.
2. Эндер-сундук и все 17 вариантов шалкера до/после Storage+.
3. Бесплатные обычные станции и настоящее размещение dirt при sneak около станции.
4. Точная граница trial и сохранение уже открытого меню.
5. Пакеты взаимодействия с обычным/странствующим торговцами и лечение zombie villager.
6. Настоящий заброс/сматывание, истечение, offhand и неизменность удочки при отказе.
7. Исключения режимов, ребёнка-жителя и животного на зарегистрированных callbacks.
8. Содержимое уведомления и отсутствие спама до/после 20 реальных серверных тиков.

Fixtures используют обычный `ServerPlayer` с EmbeddedChannel, зарегистрированным в
network tick loop, и пакет client loaded; ванильный GameTest mock с вечным creative
не используется. `gameMode.useItemOn/useItem` и `handleInteract` проходят через
фактические Fabric hooks. Capture подкласс игрока переопределяет только отправку
сообщений для assertions и вызывает `super`; режим/экономика/предметы не подменяются.

Результаты запусков фиксируются основным агентом в отчёте проверки. Наличие тестового
кода само по себе не доказывает прохождение сценариев. Клиентская предикция и звук
требуют отдельного client runtime сценария, а баланс — человеческого плейтеста.

Дополнительная особенность fixture: `AbstractVillager.overrideOffers(...)` в 26.3
пустой метод (bytecode сразу `return`). Для воспроизводимой серверной торговли тест
создаёт предложение через реальный mutable `merchant.getOffers().clear()/add(...)`.
Использование `overrideOffers` оставляло безработного тестового жителя без сделок,
из-за чего ваниль не открывала торговлю даже после успешной оплаты Trade+.
