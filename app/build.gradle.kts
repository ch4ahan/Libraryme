plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose"); id("com.google.devtools.ksp") }

android { namespace = "com.personal.novellibrary"; compileSdk = 35
 defaultConfig { applicationId = "com.personal.novellibrary"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 kotlinOptions { jvmTarget = "17" }
 buildFeatures { compose = true }
 packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
 lint { abortOnError = true; checkReleaseBuilds = true }
}

dependencies {
 val composeBom = platform("androidx.compose:compose-bom:2024.12.01"); implementation(composeBom); androidTestImplementation(composeBom)
 val roomVersion = "2.7.2"
 implementation("androidx.activity:activity-compose:1.10.1"); implementation("androidx.compose.material3:material3"); implementation("androidx.navigation:navigation-compose:2.8.5")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7"); implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
 implementation("androidx.room:room-runtime:$roomVersion"); implementation("androidx.room:room-ktx:$roomVersion"); ksp("androidx.room:room-compiler:$roomVersion")
 implementation("androidx.work:work-runtime-ktx:2.10.0"); implementation("androidx.datastore:datastore-preferences:1.1.1"); implementation("androidx.documentfile:documentfile:1.1.0")
 implementation("com.squareup.okhttp3:okhttp:4.12.0"); implementation("org.jsoup:jsoup:1.18.3"); implementation("io.coil-kt:coil-compose:2.7.0")
 testImplementation("junit:junit:4.13.2"); testImplementation("androidx.room:room-testing:$roomVersion"); testImplementation("androidx.test:core:1.6.1")
 androidTestImplementation("androidx.test.ext:junit:1.2.1"); androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1"); androidTestImplementation("androidx.compose.ui:ui-test-junit4")
 debugImplementation("androidx.compose.ui:ui-tooling"); debugImplementation("androidx.compose.ui:ui-test-manifest")
}
