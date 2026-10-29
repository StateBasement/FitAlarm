# R8 (출시 빌드 축소·난독화) 규칙
#
# - 화면(Activity)·서비스·리시버·위젯처럼 매니페스트에 적힌 클래스는 이름이 자동으로 유지됩니다.
# - ML Kit·Firebase 구성 요소는 라이브러리에 들어 있는 규칙이 자동 적용됩니다.
# - 앱 코드는 리플렉션을 쓰지 않으므로(저장은 org.json 으로 직접 읽고 씀) 추가로 지킬 클래스가 없습니다.

# 오류 보고(Play Console 의 비정상 종료 기록)에서 줄 번호를 볼 수 있게 함.
# 원래 이름으로 되돌리는 매핑 파일은 AAB 안에 함께 들어가 Play Console 이 자동으로 사용합니다.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
