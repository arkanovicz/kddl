#!/bin/bash

PWD=$(pwd)

# Find script location
SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do # resolve $SOURCE until the file is no longer a symlink
  DIR="$( cd -P "$( dirname "$SOURCE" )" >/dev/null 2>&1 && pwd )"
  SOURCE="$(readlink "$SOURCE")"
  [[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE" # if $SOURCE was a relative symlink, we need to resolve it relative to the path where the symlink file was located
done

DIR="$( cd -P "$( dirname "$SOURCE" )" >/dev/null 2>&1 && pwd )"
MAVEN="$HOME/.m2/repository"
GRADLE="$HOME/.gradle/caches/modules-2/files-2.1"

while [[ $# -gt 0 ]]
do
    case "$1" in
        -i|--input)
            INPUT="$2"
            shift ; shift
            ;;
        -f|--format)
            FORMAT="$2"
            shift ; shift
            ;;
        -q|--quoted)
            QUOTED=1
            shift;
            ;;
        -u|--uppercase)
            UPPERCASE=1
            shift;
            ;;
        -h|--help|*)
            HELP=yes
            shift
            ;;
    esac
done

is_absolute() {
  case "$1" in
           /*) true;;
           jdbc*) true;;
            *) false
  esac
}

ARGS=""
if [[ -n "$HELP" ]]; then ARGS="$ARGS -h"; fi
if [[ -n "$FORMAT" ]]; then ARGS="$ARGS -f $FORMAT"; fi
if [[ -n "$INPUT" ]]; then
    if is_absolute "$INPUT"
    then ARGS="$ARGS -i '$INPUT'"
    else ARGS="$ARGS -i '$PWD/$INPUT'"
    fi
fi
if [[ -n "$QUOTED" ]]; then ARGS="$ARGS -q"; fi
if [[ -n "$UPPERCASE" ]]; then ARGS="$ARGS -u"; fi
if [[ -z "$ARGS" ]]; then ARGS="-h"; fi

# trimming
ARGS=$(echo $ARGS | xargs)

pushd . > /dev/null
cd "$DIR"

# debugging
#GRADLE_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5008" ./gradlew run -q -Pargs="$ARGS"
# >&2 echo running ./gradlew run -q --args=\""$ARGS"\"

./gradlew --stacktrace run -q -Pargs="$ARGS"

popd > /dev/null
