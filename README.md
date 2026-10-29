# StarLight Workspace

MCP 서버, 웹 IDE 같은 도구 플러그인이 StarLight 프로젝트를 다룰 때 함께 쓰는 라이브러리 플러그인입니다. 이 플러그인 자체는 화면이 없습니다.

- **프로젝트·파일:** 목록, 읽기, 쓰기, 부분 수정, 이름 바꾸기, 삭제, 검색. 프로젝트 디렉터리 밖을 가리키는 경로는 거부합니다.
- **충돌 감지:** 파일을 읽을 때 받은 SHA-256 해시를 쓰기에 넘기면, 그사이 다른 도구가 바꾼 파일은 덮어쓰지 않습니다.
- **컴파일:** 성공/실패, 오류 위치(NeonJS·Rhino의 파일·줄·열), 그동안 쌓인 로그를 돌려줍니다.
- **이벤트:** 프로젝트가 받을 이벤트를 켜고 끕니다. `starlight.*` 같은 와일드카드는 실제 이벤트 ID로 펼쳐 저장합니다.
- **프로젝트 생성:** 앱의 새 프로젝트 화면과 같은 규칙을 따릅니다(이름 규칙, `이름.확장자` 메인 스크립트, 이벤트별 기본 코드).
- **로그:** 프로젝트·앱 로그와 실시간 스트림.
- **디버그룸:** 앱의 디버그룸처럼 `onMessage`를 호출하고 봇의 답장을 모읍니다.
- **변경 알림(`changes`):** 어느 도구(`mcp`, `ide`)나 StarLight가 무엇을 바꿨는지 알립니다. 그래서 AI가 MCP로 고친 파일을 IDE가 바로 반영할 수 있습니다.

## 사용하는 플러그인에서

`starlight.json`:

```json
"dependency": ["workspace@1.0.0"]
```

`app/build.gradle`(jar는 이 저장소의 `./gradlew :app:apiJar` 결과물 `app/build/outputs/api/workspace-api-<버전>.jar`):

```groovy
compileOnly files("libs/workspace-api-1.0.0.jar")
testImplementation files("libs/workspace-api-1.0.0.jar")
```

```kotlin
override fun onEnable() {
    // StarLight는 의존성의 major.minor만 확인하므로, 빌드에 쓴 patch 버전의 API가 있는지 직접 확인합니다.
    if (Workspaces.apiLevel < Workspaces.API_LEVEL) { /* Workspace 업데이트 안내 */ return }
    val project = Workspaces.current.project("hello-bot")
    val file = project.readFile("hello-bot.js")
    project.writeFile(file.path, file.text + "\n", file.hash, actor = "my-tool")
}
```

- `Workspaces.API_LEVEL`은 컴파일할 때 상수로 박히고, `Workspaces.apiLevel`은 설치된 Workspace의 값입니다.
- 테스트에서는 jar에 들어 있는 `dev.mooner.starlightworkspace.testing.FakeHost`로 `DefaultWorkspace(FakeHost(dir))`를 만들어 앱 없이 실행할 수 있습니다.
- 클래스는 Workspace 플러그인에서 불러오므로 jar를 `implementation`으로 넣지 마세요. 상태(로그, 변경 알림)가 플러그인마다 따로 생깁니다.

## 버전

StarLight는 의존성의 major.minor가 같으면 호환으로 봅니다.
- **하위 호환되는 추가:** patch를 올리고(1.0.x) `Workspaces.API_LEVEL`도 올립니다.
  `WorkspaceChange`의 새 종류도 여기에 속하므로, 사용하는 플러그인은 `when`으로 나눌 때 반드시 `else`를 둡니다.
- **호환을 깨는 변경:** minor 이상을 올리고, 사용하는 플러그인도 함께 다시 배포합니다.

## 빌드

```sh
./gradlew :app:assembleReleaseSlp   # app/build/outputs/starlight/release/workspace-release.slp
./gradlew :app:apiJar               # app/build/outputs/api/workspace-api-1.0.2.jar
./gradlew :app:testReleaseUnitTest
```
