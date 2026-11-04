.PHONY: check lint lint-sync lint-workflows test test-sync build release run shot emu

check: lint test

lint-sync:
	cd firebase && npm run lint

test-sync:
	cd firebase && npm test

lint-workflows:
	bash scripts/lint-workflows.sh

lint:
	./gradlew --quiet :app:lintDebug :app:checkDomainPurity

test:
	./gradlew :app:testDebugUnitTest

build:
	./gradlew --quiet :app:assembleDebug

release:
	./gradlew --quiet :app:assembleRelease

run: build
	adb install -r app/build/outputs/apk/debug/kinjar-debug.apk
	adb shell am start -n app.kinjar/.MainActivity
	sleep 3
	$(MAKE) shot

shot:
	adb exec-out screencap -p > /tmp/kinjar.png
	@echo "screenshot: /tmp/kinjar.png"

emu:
	$(HOME)/Android/Sdk/emulator/emulator -avd budget -no-window -no-audio -no-boot-anim -gpu host
