# 광고·후원(응원) 기능: 미리 만들어 둔 것과 켜는 방법

지금은 **모두 꺼져 있어** 사용자에게 아무것도 보이지 않고, 광고 SDK·결제 라이브러리도 들어 있지 않습니다.
(그래서 지금 버전의 개인정보처리방침·데이터 보안 양식은 바꿀 필요가 없습니다.)

## 1. 미리 만들어 둔 것

| 구분 | 위치 | 동작 |
|---|---|---|
| 광고 자리 | 알람 목록 맨 아래, 기록 화면 맨 아래, 설정 화면 아래 | 광고가 꺼져 있거나 후원자이면 자리 자체가 사라짐 (빈칸 없음) |
| 개발자 응원하기 카드 | 설정 탭 | 응원 상품 3개(커피 한 잔·든든한 응원·큰 응원), Play 스토어 리뷰 링크 |
| 응원 팝업 (바텀시트) | 앱 기본 화면을 열 때 | 연속 성공 7·30·100·365일, 누적 성공 20일에만 각 1번. 설치 후 7일·직전 팝업 후 30일 동안은 안 뜸. 바깥 터치·"나중에"로 닫힘, "다시 보지 않기" 있음 |
| 후원자 혜택 | 전체 | 응원하면 광고가 사라짐, 응원 팝업도 다시 안 뜸 |
| 미리보기 (개발자용) | 설정 > 정보 > **버전을 3초 안에 7번 탭** | 광고 자리를 점선 상자로 보여주고, 응원 결제는 성공한 것처럼 동작. "응원 팝업 보기", "후원 상태 초기화", "미리보기 끄기" 버튼 |

### 일부러 넣지 않은 것 (Google Play 정책)

- **알람 울림·운동 미션·보너스 팝업 화면의 광고**: 이 화면들은 잠금 화면 위에 뜨는데, Play 광고 정책은 잠금 화면에서 수익을 내는 광고를 금지합니다.
- **전면(인터스티셜) 광고**: 예기치 않게 뜨는 전면 광고는 금지되고, 아침에 알람을 끄자마자 뜨는 광고는 사용자 경험도 크게 해칩니다. 필요하면 사용자가 원해서 보는 "보상형 광고"를 따로 검토하세요.
- **토스·계좌·외부 후원 사이트 링크**: Play 결제 정책상 앱 안에서 Play 결제가 아닌 결제 수단으로 안내하는 버튼·링크는 금지(세금 공제되는 기부 등 일부 예외)입니다. 앱 안의 후원은 Play 결제로 받아야 합니다.
- **저장 버튼 등 누르는 버튼 바로 옆의 광고**: 실수로 누르게 만드는 배치는 정책 위반입니다.

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

참고: 응원 상품을 소모성으로 만들면 여러 번 응원할 수 있는 대신, 앱을 지웠다 깔면 "후원자(광고 없음)" 상태가 사라집니다.
광고 제거를 확실히 보장하려면 비소모성 "광고 제거" 상품을 따로 두는 방법도 있습니다.

## 3. 광고 켜는 방법

1. AdMob 계정을 만들고 앱을 등록한 뒤, **배너 광고 단위**를 위치별로 3개 만듭니다 (알람 목록, 기록, 설정).
2. `app/build.gradle.kts` 에 Google Mobile Ads SDK(`com.google.android.gms:play-services-ads`)를, 해외(유럽 등)에도 배포한다면 동의 SDK(UMP)도 추가합니다.
3. `AndroidManifest.xml` 에 AdMob 앱 ID(`com.google.android.gms.ads.APPLICATION_ID`)를 넣습니다.
4. `monetize/` 에 `AdProvider` 를 구현한 `AdMobProvider` 를 만들어 `Monetization.realAds` 에 넣습니다.
   `attachBanner()` 에서 `AdView` 를 만들어 container 에 넣고, `detach()` 에서 `destroy()` 합니다.
5. `MonetizationConfig.ADS_ENABLED = true`
6. 함께 바꿔야 하는 것:
   - **개인정보처리방침**: 광고 SDK 가 수집·공유하는 정보(광고 ID, 기기 정보, 대략적 위치 등)와 맞춤 광고 해제 방법 추가
   - **데이터 보안 양식**: AdMob 의 데이터 공개 문서 기준으로 "기기 또는 기타 ID", "앱 상호작용", "진단" 등을 수집·**공유**(광고 목적)로 추가
   - **앱 콘텐츠 > 광고**: "광고 포함"으로 변경
   - **app-ads.txt**: Play 스토어에 등록한 개발자 웹사이트 최상위 경로에 게시

## 4. 관련 코드

- `monetize/Monetization.kt`: 스위치(`MonetizationConfig`), 광고·결제 연결 지점(`AdProvider`, `SupportStore`), 후원자·팝업 상태
- `core/SupportPrompt.kt`: 응원 팝업을 띄울 순간을 고르는 규칙 (단위 테스트: `SupportPromptTest`)
- `ui/MonetizeViews.kt`: 광고 자리(`AdSlotView`), 응원 카드·팝업, 미리보기 카드

## 근거

- [Google Play 결제 정책](https://support.google.com/googleplay/android-developer/answer/9858738)
- [Google Play 광고 정책](https://support.google.com/googleplay/android-developer/answer/9857753)
- [한국 대체 결제 시스템 안내](https://support.google.com/googleplay/android-developer/answer/11222040)
