/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import { AppRegistry } from 'react-native';
import App from './App';
import { LookupSheetApp } from './src/rn/ui/lookup/LookupSheetApp';

AppRegistry.registerComponent('Gaten', () => App);
// The lookup overlay's own root: same bundle, same React host, mounted by the accessibility
// service's window instead of the activity.
AppRegistry.registerComponent('GatenLookupSheet', () => LookupSheetApp);
