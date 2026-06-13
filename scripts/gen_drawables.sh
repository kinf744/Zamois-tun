#!/bin/bash
# Generate all missing drawable XML files

DRAWABLE_DIR="/home/claude/KIGHMU-VPN/app/src/main/res/drawable"

# ic_settings.xml
cat > "$DRAWABLE_DIR/ic_settings.xml" << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FF8B949E"
        android:pathData="M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94 0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6 0,-1.98 1.62,-3.6 3.6,-3.6 1.98,0 3.6,1.62 3.6,3.6 0,1.98 -1.62,3.6 -3.6,3.6z"/>
</vector>
EOF

# ic_settings_input.xml (config tab)
cat > "$DRAWABLE_DIR/ic_settings_input.xml" << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FF8B949E"
        android:pathData="M20,2H4C2.9,2 2,2.9 2,4v3.01C2,7.73 2.43,8.35 3,8.7V20c0,1.1 1.1,2 2,2h14c0.9,0 2,-0.9 2,-2V8.7c0.57,-0.35 1,-0.97 1,-1.69V4C22,2.9 21.1,2 20,2zM19,20H5V9h14V20zM20,7H4V4h16V7zM9,12h6v2H9V12z"/>
</vector>
EOF

# ic_terminal.xml (logs tab)
cat > "$DRAWABLE_DIR/ic_terminal.xml" << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FF8B949E"
        android:pathData="M20,3H4C2.89,3 2,3.89 2,5v14c0,1.1 0.89,2 2,2h16c1.1,0 2,-0.9 2,-2V5C22,3.89 21.1,3 20,3zM20,19H4V7h16V19zM13,8v2h4V8H13zM6.41,15L8.83,12.59 7.41,11.17 3.59,15l3.82,3.83 1.41,-1.42L6.41,15z"/>
</vector>
EOF

# ic_close.xml
cat > "$DRAWABLE_DIR/ic_close.xml" << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"/>
</vector>
EOF

# ic_upload.xml
cat > "$DRAWABLE_DIR/ic_upload.xml" << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FF8B949E"
        android:pathData="M9,16h6v-6h4l-7,-7 -7,7h4v6zM5,18h14v2H5z"/>
</vector>
EOF

# ic_download.xml
cat > "$DRAWABLE_DIR/ic_download.xml" << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FF8B949E"
        android:pathData="M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z"/>
</vector>
EOF

# bg_spinner.xml
cat > "$DRAWABLE_DIR/bg_spinner.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#FF21262D"/>
    <stroke android:width="1dp" android:color="#FF30363D"/>
    <corners android:radius="8dp"/>
</shape>
EOF

# splash_background.xml
cat > "$DRAWABLE_DIR/splash_background.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@color/background_dark"/>
    <item>
        <bitmap
            android:src="@drawable/ic_vpn_lock_on"
            android:gravity="center"/>
    </item>
</layer-list>
EOF

# nav_item_color.xml (color state list)
cat > "/home/claude/KIGHMU-VPN/app/src/main/res/color/nav_item_color.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:color="@color/accent_blue" android:state_checked="true"/>
    <item android:color="@color/text_secondary"/>
</selector>
EOF

echo "All drawables created"
