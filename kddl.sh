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

# Using Java

#java -Dfile.encoding=UTF-8 -cp $DIR/build/classes/java/main:$DIR/build/classes/kotlin/jvm/main:$DIR/build/processedResources/jvm/main:$GRADLE/org.jetbrains.kotlinx/kotlinx-cli-jvm/0.3/24643f52052c849d6aa69af75b2d52128c611968/kotlinx-cli-jvm-0.3.jar:$GRADLE/org.jetbrains.kotlin/kotlin-stdlib-jdk8/1.4.10/998caa30623f73223194a8b657abd2baec4880ea/kotlin-stdlib-jdk8-1.4.10.jar:$GRADLE/org.jetbrains.kotlin/kotlin-stdlib-jdk7/1.4.10/30e46450b0bb3dbf43898d2f461be4a942784780/kotlin-stdlib-jdk7-1.4.10.jar:$GRADLE/org.jetbrains.kotlin/kotlin-stdlib/1.4.10/ea29e063d2bbe695be13e9d044dcfb0c7add398e/kotlin-stdlib-1.4.10.jar:$GRADLE/org.jetbrains.kotlin/kotlin-stdlib-common/1.4.10/6229be3465805c99db1142ad75e6c6ddeac0b04c/kotlin-stdlib-common-1.4.10.jar:$GRADLE/org.jetbrains/annotations/13.0/919f0dfe192fb4e063e7dacadee7f8bb9a2672a9/annotations-13.0.jar com.republicate.kddl.MainKt $*

# Using gradle

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

pushd . > /dev/null
cd "$DIR"
#GRADLE_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5008" ./gradlew run -q --args="$ARGS"
# >&2 echo running ./gradlew run -q --args=\""$ARGS"\"
./gradlew run -q --args="$ARGS"
popd > /dev/null
