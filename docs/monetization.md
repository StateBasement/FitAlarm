# 광고·후원(응원) 기능

- **광고: 켜짐 (2.4.0~)** Google AdMob 배너 3곳. 출시 빌드만 실제 광고, 테스트 빌드는 테스트 광고
- **후원(응원): 꺼짐.** 코드와 화면은 만들어 두었고, Play 결제를 연결하면 켤 수 있음 (2항)

## 1. 화면 구성

| 구분 | 위치 | 동작 |
|---|---|---|
| 광고 | 알람 탭 맨 아래, 기록 탭 맨 아래, 설정 탭 맨 아래 | 광고가 실제로 도착했을 때만 자리가 생김 (채울 광고가 없으면 빈칸 없음). 탭마다 광고 단위 하나 |
| 개발자 응원하기 카드 | 설정 탭 | 응원 상품 3개(커피 한 잔·든든한 응원·큰 응원), Play 스토어 리뷰 링크 (후원을 켜야 보임) |
| 응원 팝업 (바텀시트) | 앱 기본 화면을 열 때 | 연속 성공 7·30·100·365일, 누적 성공 20일에만 각 1번. 설치 후 7일·직전 팝업 후 30일 동안은 안 뜸. 바깥 터치·"나중에"로 닫힘, "다시 보지 않기" 있음 |
| 후원자 혜택 | 전체 | 응원하면 광고가 사라짐, 응원 팝업도 다시 안 뜸 |
| 광고 개인정보 설정 | 설정 > 정보 | 유럽·영국 등 동의가 필요한 지역의 사용자에게만 보임 (동의 변경) |
| 미리보기 (개발자용) | 설정 > 정보 > **버전을 3초 안에 7번 탭** | 광고 자리를 점선 상자로 보여주고, 응원 결제는 성공한 것처럼 동작. "응원 팝업 보기", "후원 상태 초기화", "미리보기 끄기" 버튼 |

### 일부러 넣지 않은 것 (Google Play·AdMob 정책)

- **알람 울림·운동 미션·보너스 팝업 화면의 광고**: 이 화면들은 잠금 화면 위에 뜨는데, Play 광고 정책은 잠금 화면에서 수익을 내는 광고를 금지합니다. 광고 SDK 는 기본 화면(MainActivity)에서만 시작합니다.
- **전면(인터스티셜) 광고**: 예기치 않게 뜨는 전면 광고는 금지되고, 아침에 알람을 끄자마자 뜨는 광고는 사용자 경험도 크게 해칩니다. 필요하면 사용자가 원해서 보는 "보상형 광고"를 따로 검토하세요.
- **버튼 바로 옆·내비게이션과 내용 사이의 광고**: 실수로 누르게 만드는 배치는 AdMob 정책 위반입니다. 광고는 각 탭 내용의 맨 끝에 두고 위 카드와 16~20dp 띄웠습니다.
- **다른 요소에 가려지는 광고**: 알람 탭에서 스크롤하다 광고가 오른쪽 아래 + 버튼 밑으로 지나가면, 겹치는 동안만 + 버튼을 숨깁니다.
- **토스·계좌·외부 후원 사이트 링크**: Play 결제 정책상 앱 안에서 Play 결제가 아닌 결제 수단으로 안내하는 버튼·링크는 금지(세금 공제되는 기부 등 일부 예외)입니다.

## 2. 후원(응원) 켜는 방법

1. Play Console > 수익 창출 설정에서 **판매자(결제) 프로필**을 만듭니다.
2. Play Console > 수익 창출 > 상품 > **인앱 상품**에서 아래 ID 로 상품 3개를 만들고 가격을 정합니다.
   - `support_coffee` (예: ₩1,100), `support_cheer` (예: ₩3,300), `support_big` (예: ₩5,500)
3. `app/build.gradle.kts` 에 Google Play 결제 라이브러리(최신 버전)를 추가합니다: `com.android.billingclient:billing-ktx`
4. `monetize/` 에 `SupportStore` 를 구현한 `PlayBillingSupportStore` 를 만들어, 앱 시작 시 `Monetization.realStore` 에 넣습니다.
   - `products()`: Play 에서 상품 정보를 조회해 현지 가격(`price`)을 채움
   - `purchase()`: 결제 화면을 띄우고, 성공하면 **3일 안에 소모(consume) 처리**해야 자동 환불되지 않음
5. `MonetizationConfig.SUPPORT_ENABLED = true`
6. 비공개 테스트 트랙에 올린 뒤 **라이선스 테스터** 계정으로 결제를 시험합니다 (실제 돈이 나가지 않음).
7. 개인정보처리방침·데이터 보안 양식에 "구매 내역"을 추가하고 이용약관 제8조를 확인합니다.

참고: 응원 상품을 소모성으로 만들면 여러 번 응원할 수 있는 대신, 앱을 지웠다 깔면 "후원자(광고 없음)" 상태가 사라집니다.
광고 제거를 확실히 보장하려면 비소모성 "광고 제거" 상품을 따로 두는 방법도 있습니다.

## 3. 광고 (AdMob)

### 연결된 ID (공개 값, 비밀 아님)

| 구분 | ID |
|---|---|
| 앱 ID | `ca-app-pub-9101880128165365~5622811936` |
| 알람 목록 (배너) | `ca-app-pub-9101880128165365/8573116741` |
| 기록 (배너) | `ca-app-pub-9101880128165365/1923947916` |
| 설정 (배너) | `ca-app-pub-9101880128165365/5755381718` |

- 코드: `monetize/AdMobIds.kt` (광고 단위), `AndroidManifest.xml` (앱 ID, 광고 동의 SDK 용). 단위 테스트 `AdMobIdsTest` 가 둘이 같은지, 테스트 빌드에 실제 광고가 들어가지 않는지 검사합니다.
- SDK: Google 차세대 모바일 광고 SDK(GMA Next-Gen, `ads-mobile-sdk`) + 광고 동의 SDK(UMP). Google 이 2026년 7월부터 권장하는 SDK 입니다 (기존 `play-services-ads` 는 2027년 6월 지원 종료 예정).

### 빌드별 광고

| 빌드 | 광고 | 용도 |
|---|---|---|
| Releases 의 `FitAlarm.apk` (QA), debug | **Google 테스트 광고** ("Test Ad" 표시) | 폰에 직접 설치해 확인 |
| Play 스토어용 AAB (release) | **실제 광고** | Play Console 에 업로드 |

**주의: 실제 광고가 나오는 스토어 버전에서 내 광고를 누르거나 반복해서 보지 마세요.** 무효 트래픽으로 AdMob 계정이 정지될 수 있습니다.
스토어 버전으로 확인하고 싶으면 AdMob > 설정 > 기기 테스트 에 내 폰을 테스트 기기로 등록하세요.

### 실제 광고가 나오기까지 (AdMob 화면의 다음 단계)

1. ~~광고 단위 만들기, SDK 연결~~ (완료)
2. Play 스토어에 앱 게시 (비공개·공개 테스트가 아닌 프로덕션 공개 후 검색 가능해져야 함)
3. AdMob > 앱 > Fitalarm > 앱 설정 > **앱 스토어 세부정보 추가** 에서 Play 스토어의 앱을 연결
4. AdMob 이 앱을 검토 (보통 며칠). 승인 전까지는 광고가 거의 나오지 않는 "광고 게재 제한" 상태가 정상입니다.
5. **app-ads.txt** 게시 (아래)

### app-ads.txt (광고 수익 보호, 강력 권장)

Play 스토어 등록정보의 **개발자 웹사이트** 최상위 경로에 아래 한 줄짜리 파일을 올립니다. 내용은 저장소의 `docs/app-ads.txt` 와 같습니다.

```
google.com, pub-9101880128165365, DIRECT, f08c47fec0942fa0
```

웹사이트가 없으면 무료 GitHub Pages 를 쓸 수 있습니다.
1. GitHub 에서 이름이 정확히 `StateBasement.github.io` 인 **공개** 저장소를 만듭니다.
2. 저장소 맨 위에 `app-ads.txt` 파일을 위 내용으로 만듭니다. → `https://statebasement.github.io/app-ads.txt` 로 열리는지 확인
3. Play Console > 기본 스토어 등록정보 > 웹사이트에 `https://statebasement.github.io` 입력
4. AdMob > 앱 > app-ads.txt 탭에서 "업데이트 확인" (반영에 최대 24시간)

### 광고 동의 (유럽 등)

- 한국 사용자에게는 동의 화면이 뜨지 않습니다. 맞춤형 광고에 대한 안내와 거부 방법은 개인정보처리방침 5항에 있습니다.
- **Play Console 에서 배포 국가를 대한민국만** 고르면 추가 작업이 없습니다.
- 유럽·영국·스위스에도 배포하려면 AdMob > 개인정보 보호 및 메시지 > **유럽 규정** 메시지를 만들어 게시하세요. 앱은 이미 동의 화면 표시(UMP)와 "광고 개인정보 설정" 메뉴를 갖추고 있습니다. 메시지가 없으면 그 지역에서는 광고가 제한됩니다.

### 광고 내용 관리 (선택)

AdMob > 차단 관리 에서 광고 콘텐츠 등급(예: "청소년 이상 T")과 민감한 카테고리(도박, 데이트 등)를 막을 수 있습니다. 건강 앱 분위기에 맞지 않는 광고가 보이면 여기서 차단하세요.

### 광고를 끄려면

`MonetizationConfig.ADS_ENABLED = false` 로 바꾸면 광고 SDK 를 시작하지 않고 자리도 생기지 않습니다. 이때는 개인정보처리방침·데이터 보안 양식·이용약관 제9조도 함께 되돌리세요.

## 4. 관련 코드

- `monetize/Monetization.kt`: 스위치(`MonetizationConfig`), 광고·결제 연결 지점(`AdProvider`, `SupportStore`), 후원자·팝업 상태
- `monetize/AdMob.kt`: 광고 동의 → SDK 초기화(백그라운드) → 배너(`AdMobProvider`)
- `monetize/AdMobIds.kt`: 앱 ID·광고 단위 ID, 빌드별 테스트/실제 선택
- `core/SupportPrompt.kt`: 응원 팝업을 띄울 순간을 고르는 규칙 (단위 테스트: `SupportPromptTest`)
- `ui/MonetizeViews.kt`: 광고 자리(`AdSlotView`), 응원 카드·팝업, 미리보기 카드

## 근거

- [AdMob 배너 구현 안내 (권장하지 않는 배치)](https://support.google.com/admob/answer/6275345)
- [GMA Next-Gen SDK 시작하기](https://developers.google.com/admob/android/next-gen/quick-start)
- [UMP(광고 동의) SDK 설정](https://developers.google.com/admob/android/next-gen/privacy)
- [Google Play 광고 정책](https://support.google.com/googleplay/android-developer/answer/9857753)
- [Google Play 결제 정책](https://support.google.com/googleplay/android-developer/answer/9858738)
- [한국 대체 결제 시스템 안내](https://support.google.com/googleplay/android-developer/answer/11222040)
