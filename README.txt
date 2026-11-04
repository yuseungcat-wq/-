Bazaar Hint (Fabric, Minecraft 26.2)  -  표시 전용 모드

[필요한 것] Java 25 JDK, Gradle 9.5.1 이상, Minecraft 26.2 + Fabric Loader 0.19.5+ + Fabric API(26.2용)

[빌드]
1) 이 폴더에서  gradle wrapper --gradle-version 9.5.1   (한 번만. Gradle이 없으면 IntelliJ IDEA로 폴더를 열어도 됩니다)
2) gradlew build        (윈도우: gradlew.bat build)
3) build/libs/bazaar-hint-1.1.0.jar 를 .minecraft/mods 폴더에 넣기

[사용]
1) 먼저 bazaar_web.py(또는 run_bazaar.bat)를 실행해 대시보드를 켭니다. (localhost:8000)
2) 게임 접속 -> 화면 오른쪽 위에 추천 리스트가 15초마다 갱신됩니다.
3) 바자 창을 열면 사야 할 재료는 초록, 완성품은 금색으로 강조됩니다.

[설정] 표시 탭/개수는 JVM 옵션으로: -Dbazaarhint.url="http://localhost:8000/api/mod?tab=chain&n=6"
       (tab = craft | chain | spread | npc)  /  세금·방식 옵션도 같은 주소 뒤에 &tax=1.0&ing=order&sell=instant 처럼 붙일 수 있음
[주의] 하이픽셀 허용 모드 목록에 없는 모드는 하이픽셀이 권장하지 않습니다. 이 모드는 화면 표시만 하며 클릭/구매를 대신하지 않습니다.


[v1.1 추가] 바자 주문 앞지름 알림
- 내 주문 파악: '주문 관리' 창을 열면 내 주문(구매/판매, 개당 가격)을 읽고, 주문 생성/체결 채팅 메시지로도 갱신합니다.
  (주문 취소는 채팅으로 알 수 없으니, 가끔 '주문 관리' 창을 열어 목록을 다시 맞춰 주세요.)
- 앞지름 감지: 대시보드(/api/quotes)의 최고 구매 주문/최저 판매 주문과 비교 (15초마다, 같은 알림은 5분에 한 번).
- 채팅에 [바자 열기] (/bz 아이템 실행) 와 [새 가격 복사] (클립보드) 버튼이 나옵니다. 버튼 한 번 = 명령 한 번이며,
  주문 취소/재등록은 직접 하셔야 합니다. (1.21.5 이후 버전은 채팅 명령 실행 시 확인 창이 뜰 수 있습니다.)
- '주문 관리' 창에서는 앞지름당한 주문 칸이 빨갛게 표시되고, 오른쪽 위 리스트에 '갱신 필요 주문 N개'가 표시됩니다.
- 대시보드 주소가 다르면 JVM 옵션  -Dbazaarhint.base=http://localhost:8000  을 바꾸세요.
- 대시보드(bazaar_web.py)를 이번 버전으로 덮어써야 /api/quotes 가 동작합니다.
