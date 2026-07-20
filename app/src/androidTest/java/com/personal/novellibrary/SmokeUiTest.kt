package com.personal.novellibrary

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class SmokeUiTest { @get:Rule val rule=createAndroidComposeRule<MainActivity>(); @Test fun homeShowsFolderScan(){ rule.onNodeWithText("TXT 폴더 선택/스캔").assertExists() } }
