#!/bin/zsh
# Download the GTA VI artwork, logo and skyline video from rockstargames.com into res/.
# They belong to Rockstar Games, so they aren't kept in this repository; build.sh runs this
# when they're missing. Images are scaled to 2000px wide to keep the APK small.
# Usage: launcher/fetch-art.sh [--force]
set -euo pipefail
cd "${0:A:h}"

SITE=https://www.rockstargames.com
MEDIA=$SITE/VI/_next/static/media
UA="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"
ART=res/drawable-nodpi
mkdir -p $ART res/raw

# res file             official name on /VI/media/artwork-wallpapers   last known file (fallback)
IMAGES=(
  vice_background.jpg  Jason_and_Lucia_01_landscape      Jason_and_Lucia_01_landscape.12x2gvspcm_3m.jpg
  vice_art_1.jpg       Brian_Heder_landscape             Brian_Heder_landscape.0a-egj5b8yo1q.jpg
  vice_art_2.jpg       Real_Dimez_landscape              Real_Dimez_landscape.0637akp_a5a_q.jpg
  vice_art_3.jpg       DreQuan_Priest_landscape          DreQuan_Priest_landscape.0_b7hszyze6cy.jpg
  vice_art_4.jpg       Raul_Bautista_landscape           Raul_Bautista_landscape.11_3hd0fr69~j.jpg
  vice_art_5.jpg       Boobie_Ike_landscape              Boobie_Ike_landscape.0ldnbn87k-8mq.jpg
  vice_art_6.jpg       Cal_Hampton_landscape             Cal_Hampton_landscape.17k7bnt3myg.2.jpg
  vice_art_7.jpg       Jason_and_Lucia_02_landscape      Jason_and_Lucia_02_landscape.0cosv-uzbpt91.jpg
  vice_art_8.jpg       Jason_and_Lucia_Beach_landscape   Jason_and_Lucia_Beach_landscape.0419q._86ukpt.jpg
  vice_art_9.jpg       Jason_Lucia_Lock_And_Load_landscape Jason_Lucia_Lock_And_Load_landscape.0l9s5w7ih9p~3.jpg
  vice_art_10.jpg      Jason_and_Lucia_Robbery_landscape Jason_and_Lucia_Robbery_landscape.09c8a~do21h4p.jpg
)
LOGO=vi.0hxa9~pf214xe.png
VIDEO=1920.0bnh1cyqg24b..mp4

force=${1:-}
need() { [[ "$force" == --force || ! -s "$1" ]]; }

get() {  # url file
  curl -fsSL -A "$UA" -e "$SITE/VI" --retry 2 -o "$2.part" "$1" && mv "$2.part" "$2"
}

# The site renames files when it updates, so look each one up by name first.
page=$(curl -fsSL -A "$UA" "$SITE/VI/media/artwork-wallpapers" 2>/dev/null || true)
current() {  # official name -> current file name, or nothing
  print -r -- "$page" | grep -o "/VI/_next/static/media/$1\.[^\"'\\\\ )]*\.jpg" | head -1 | sed 's#.*/##'
}

for (( i = 1; i <= ${#IMAGES}; i += 3 )); do
  out=$ART/${IMAGES[i]} name=${IMAGES[i+1]} known=${IMAGES[i+2]}
  need $out || continue
  file=$(current $name); [[ -n "$file" ]] || file=$known
  echo "• $name"
  if ! get "$MEDIA/$file" $out.src && { [[ "$file" == "$known" ]] || ! get "$MEDIA/$known" $out.src; }; then
    echo "  couldn't download $name from rockstargames.com" >&2
    exit 1
  fi
  # Scale down with macOS sips, or ImageMagick elsewhere; if neither is there, keep the full size.
  if command -v sips >/dev/null; then sips -s format jpeg -s formatOptions 86 -Z 2000 $out.src --out $out >/dev/null
  elif command -v magick >/dev/null; then magick $out.src -resize '2000x2000>' -quality 86 $out
  elif command -v convert >/dev/null; then convert $out.src -resize '2000x2000>' -quality 86 $out
  else mv $out.src $out; fi
  rm -f $out.src
done

if need $ART/vice_logo.png; then
  echo "• logo"
  file=$(print -r -- "$page" | grep -o "/VI/_next/static/media/vi\.[^\"'\\\\ )]*\.png" | head -1 | sed 's#.*/##')
  get "$MEDIA/${file:-$LOGO}" $ART/vice_logo.png || get "$MEDIA/$LOGO" $ART/vice_logo.png
fi

if need res/raw/vice_skyline.mp4; then
  echo "• skyline video"
  # Without the video the home screen simply shows the key art instead.
  get "$MEDIA/$VIDEO" res/raw/vice_skyline.mp4 || { echo "  video unavailable; using the still image" >&2; : > res/raw/vice_skyline.mp4; }
fi
echo "Artwork ready."
