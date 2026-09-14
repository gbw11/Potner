#!/usr/bin/env bash
# 시연용 성장 사진을 장치 업로드 API 로 넣는다.
#
# 왜 SQL 이 아닌가: plant_photo 는 경로만 저장하고 실제 파일은 디스크에 있어야 한다.
# 원본·재생본·썸네일 세 장을 서버가 만들어 주므로, 행을 손으로 넣으면 이미지가 깨진 채
# 목록만 늘어난다. 장치와 같은 경로로 올리면 파일과 행이 함께 만들어진다.
#
# 하루 한 장 제한이 있어(같은 날 두 번 올리면 409) 날짜를 하루씩 거슬러 올린다.
# capturedAt 을 지정할 수 있으므로 지난 날짜도 채울 수 있다.
#
# 선행 조건:
#   1. seed-demo-data.sql 을 먼저 돌린다 — 업로드 토큰과 로봇·식물 배정을 그 스크립트가 심는다.
#   2. 올릴 이미지가 있어야 한다. 아무 JPEG/PNG 여도 되고, 여러 장을 주면 날짜별로 돌려 쓴다.
#      앱 저장소의 assets/images/home/stitch_rose_photo.jpg 같은 파일을 그대로 써도 된다.
#
# 사용:
#   ./seed-demo-photos.sh --images ./demo-photos
#   ./seed-demo-photos.sh --images ./demo-photos --days 14 --base-url http://localhost:8080
#
# 업로드가 끝나면 seed-demo-data.sql 을 한 번 더 돌린다. 대표 사진이 지정되어 홈과
# 식물 목록의 썸네일이 채워진다.

set -euo pipefail

BASE_URL="http://localhost:8080"
TOKEN="demo-upload-token-fff"
DAYS=21
IMAGE_DIR=""

usage() {
    cat <<'USAGE'
사용법: seed-demo-photos.sh --images <디렉토리> [옵션]

  --images   <dir>   올릴 이미지가 있는 디렉토리 (필수). jpg·jpeg·png 를 찾는다.
  --days     <n>     오늘부터 며칠치를 채울지. 기본 21
  --base-url <url>   서버 주소. 기본 http://localhost:8080
  --token    <t>     업로드 토큰. 기본 demo-upload-token-fff (seed-demo-data.sql 과 같은 값)
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --images)   IMAGE_DIR="$2"; shift 2 ;;
        --days)     DAYS="$2"; shift 2 ;;
        --base-url) BASE_URL="${2%/}"; shift 2 ;;
        --token)    TOKEN="$2"; shift 2 ;;
        -h|--help)  usage; exit 0 ;;
        *) echo "알 수 없는 옵션: $1" >&2; usage; exit 1 ;;
    esac
done

if [[ -z "$IMAGE_DIR" ]]; then
    echo "오류: --images 로 이미지 디렉토리를 지정해야 합니다." >&2
    usage
    exit 1
fi

if [[ ! -d "$IMAGE_DIR" ]]; then
    echo "오류: 디렉토리를 찾을 수 없습니다: $IMAGE_DIR" >&2
    exit 1
fi

# 파일 이름에 공백이 있어도 깨지지 않게 배열로 담는다.
mapfile -t IMAGES < <(find "$IMAGE_DIR" -maxdepth 1 -type f \
    \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' \) | sort)

if [[ ${#IMAGES[@]} -eq 0 ]]; then
    echo "오류: $IMAGE_DIR 안에 jpg·jpeg·png 파일이 없습니다." >&2
    exit 1
fi

echo "서버:      $BASE_URL"
echo "이미지:    ${#IMAGES[@]}장 (날짜별로 돌려 씁니다)"
echo "채울 기간: 오늘부터 ${DAYS}일"
echo

uploaded=0
skipped=0
failed=0

for ((i = 0; i < DAYS; i++)); do
    # 서비스 타임존(KST) 기준 낮 시각으로 맞춘다. UTC 04:00 = KST 13:00 이라
    # photo_date 가 의도한 날짜로 떨어진다.
    day="$(date -u -d "${i} days ago" +%Y-%m-%d)"
    captured_at="${day}T04:00:00Z"

    image="${IMAGES[$((i % ${#IMAGES[@]}))]}"

    # 응답 본문과 상태 코드를 함께 받는다. 409 는 그날 사진이 이미 있다는 뜻이라 건너뛴다.
    response="$(curl -sS -o /tmp/seed-photo-body -w '%{http_code}' \
        -X POST "${BASE_URL}/api/v1/device/photos?capturedAt=${captured_at}" \
        -H "X-Device-Token: ${TOKEN}" \
        -F "file=@${image}" || echo '000')"

    case "$response" in
        20*)
            uploaded=$((uploaded + 1))
            printf '  %s  올림   (%s)\n' "$day" "$(basename "$image")"
            ;;
        409)
            skipped=$((skipped + 1))
            printf '  %s  건너뜀 (이미 그날 사진이 있습니다)\n' "$day"
            ;;
        *)
            failed=$((failed + 1))
            printf '  %s  실패   HTTP %s — %s\n' \
                "$day" "$response" "$(head -c 200 /tmp/seed-photo-body)"
            ;;
    esac
done

rm -f /tmp/seed-photo-body

echo
echo "올림 ${uploaded}건 / 건너뜀 ${skipped}건 / 실패 ${failed}건"

if [[ $failed -gt 0 ]]; then
    cat <<'HINT'

실패가 있으면 아래를 확인하세요.
  - 401: 업로드 토큰이 다릅니다. seed-demo-data.sql 을 먼저 돌렸는지 확인하세요.
  - 404: 로봇에 식물이 배정되지 않았습니다. seed-demo-data.sql 이 배정을 심습니다.
         (이미 다른 로봇이 그 식물을 담당하고 있으면 배정을 건너뛰므로, 앱의 장치 관리에서
          기존 배정을 해제한 뒤 다시 돌리세요.)
  - 413: Nginx 업로드 크기 제한입니다. 더 작은 이미지를 쓰세요.
HINT
    exit 1
fi

echo "끝났습니다. seed-demo-data.sql 을 한 번 더 돌리면 대표 사진이 지정됩니다."
