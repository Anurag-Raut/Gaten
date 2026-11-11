/**
 * Gaten — evidence fixture entry. NOT part of the app: nothing in `src/` imports it, and it is
 * only compiled into a build that opts in with the React Native Gradle plugin's own ENTRY_FILE.
 *
 * From the repo root, with this directory copied to `.evidence-fixture/` (see build-fixture-apk.sh):
 *
 *   ENTRY_FILE=.evidence-fixture/index.js ./gradlew --no-daemon :app:assembleDebug \
 *     -PreactNativeArchitectures=x86_64
 */
import {AppRegistry} from 'react-native';
import {ReviewAnswerFixture} from './ReviewAnswerFixture';

AppRegistry.registerComponent('Gaten', () => ReviewAnswerFixture);
