# 📱 Offline Document Composer (DocStudio)

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.21-purple.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-blue.svg)](https://developer.android.com/jetpack/compose)
[![OpenCV](https://img.shields.io/badge/OpenCV-4.10.0-red.svg)](https://opencv.org/)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-34%20(Android%2014)-teal.svg)](https://developer.android.com/)
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline-success.svg)](#-privacy--security-first)
[![APK Size](https://img.shields.io/badge/Release%20APK-49.4%20MB-orange.svg)](#-download--installation)

A high-performance, **100% offline, privacy-first Android document workspace**. Built with **Jetpack Compose**, **OpenCV C++ Native Engine**, and **PDFBox**, **Offline Document Composer** replaces multiple separate apps with a unified, high-speed utility:
1. **Smart Live Camera Document Scanner** with OpenCV real-time edge detection & perspective warp.
2. **Professional Document Enhancer** with B&W crisp filtering & instant-response tuning sliders.
3. **Multi-Page Canvas Composer** with drag-and-drop page reordering, digital signatures, and watermarks.
4. **Biometric Passport Photo Maker** matching Indian & global government standards with multi-copy print sheet tiling.
5. **Smart Image Resizer & Compressor** with exact target KB compression for government job portals (UPSC, SSC, State PSC).
6. **Direct Android Print System & PDF Studio** for instant Wi-Fi printing and vector PDF generation.

---

## 📥 Download & Installation

- **Pre-built Release APK**: [`app/release/DocStudio.apk`](app/release/DocStudio.apk) (Ready to install on any Android phone running Android 8.0+)
- **Full User Manual Presentation**: [`Offline_Document_Composer_User_Manual.pptx`](Offline_Document_Composer_User_Manual.pptx) (14-slide executive presentation)

### Quick Install via ADB:
```bash
adb install -r app/release/DocStudio.apk
```
*Or simply copy `DocStudio.apk` to your phone and tap to install.*

---

## 🔒 Privacy & Security First

- **Zero Cloud Uploads**: Your files, photos, signatures, and ID cards never leave your physical device.
- **No Internet Permission Required**: The app does not even request the Android `INTERNET` permission in its manifest.
- **No Logins or Accounts**: Zero personal data collection, zero analytics, zero trackers, and zero advertisements.
- **Local Storage Ownership**: All exported PDFs and images are stored directly in your device's standard `Documents/DocComposer` and `Pictures/ResizedImages` directories, allowing effortless transfer to your PC via USB.

---

## 🚀 Key Modules & Features

### 1. 📷 Smart Live Document Scanner
- **Real-Time Edge Snapping**: OpenCV computer vision tracks document borders in real time with an interactive polygon overlay.
- **Automatic Perspective Correction**: Warps tilted or angled photos into perfectly flat, rectangular top-down documents.
- **Precision Loupe Magnifier**: Touch any corner on the crop screen to trigger a 2.5× zoom loupe for millimeter-accurate boundary adjustments.
- **Continuous Batch Capture**: Scan multi-page contracts, book chapters, or receipts in seconds without leaving the viewfinder.
- **Torch / Flashlight Support**: Toggle flashlight for crystal-clear scans in low-light environments.
- **Gallery Import**: Pick pre-existing photos or document images directly from your photo library.

---

### 2. 🎨 Document Enhancement & Filter Studio
- **Document Crisp (B&W)**: Cleans dirty paper shadows and yellowish tints, converting background to pure white and text to sharp black.
- **Magic Color**: Boosts vibrancy, sharpness, and clarity while preserving colored stamps, official seals, and signatures.
- **Grayscale & High-Contrast**: Ideal for archiving historical records, pencil drawings, or faint carbon copies.
- **Zero-Lag Interactive Sliders**: Adjust Brightness, Contrast, Saturation, and Sharpness smoothly. Uses a lightweight 1080px preview canvas with coroutine job cancellation to deliver **3ms response times** without UI lag.
- **Transformations**: One-tap 90° clockwise/counter-clockwise rotation and freeform crop.

---

### 3. 📄 Multi-Page Canvas Composer
- **Visual Page Grid**: View all document pages as interactive cards with instant cached thumbnails.
- **Drag-and-Drop Reordering**: Long-press and drag any page to customize reading and printing sequence.
- **Multi-Layer Overlay Studio**:
  - **Digital Signatures**: Draw signatures directly on screen with adjustable stroke thickness and color, then place them anywhere on the page.
  - **Official Watermarks**: Add customizable stamps (e.g. `CONFIDENTIAL`, `SAMPLE`, `ORIGINAL`, `APPROVED`) with opacity control.
  - **Text Annotations**: Add notes, dates, or form fields with custom typography and colors.
  - **Interactive Gestures**: Pinch to scale, rotate with two fingers, and drag layers across the canvas.
- **Undo / Redo History**: Revert or replay any canvas modification with a dedicated safety stack.

---

### 4. 👤 Passport Size Photo Maker (Indian & Global Standards)
Create studio-quality passport and visa photo sheets ready for printing:
- **Official Biometric Presets**:
  - **Indian Passport / Visa**: Standard 35 × 45 mm (3.5 × 4.5 cm).
  - **PAN Card Application**: Standard 25 × 35 mm (2.5 × 3.5 cm) for NSDL / UTI forms.
  - **Official Stamp Size**: Standard 20 × 25 mm format for government registries and ID cards.
  - **US Visa / Green Card**: Square 2 × 2 inches (51 × 51 mm / 600×600 px).
  - **Custom Size**: Specify custom width and height in millimeters.
- **Biometric Crop Guide**: Face oval with chin, crown, and eye-level alignment markers adhering to international ICAO specifications.
- **Multi-Copy Grid Auto-Tiling**: Select 1, 2, 4, 6, 8, 12, 16, or 32 copies. The app automatically arranges and spaces them onto standard **4×6 inch photo paper** or **A4 sheets**.
- **Cutting Guide Borders**: Draws subtle border lines between photos for effortless trimming with scissors or paper cutters.
- **Flexible Export**: Save as high-res JPG, 300 DPI PDF, or print directly.

---

### 5. 📐 Smart Image Resizer & File Size Compressor
Designed specifically for online job applications, civil service exams, and admissions portals (UPSC, SSC, State PSC, IBPS, GATE, NEET):
- **Exact Target File Size Mode (e.g. Under 50 KB or Under 20 KB)**:
  - Enter your target size in kilobytes (KB).
  - Iterative binary-search compression algorithm adjusts quality and dimensions to hit the exact size limit while preserving maximum visual legibility.
- **Dimension Scaling Mode**:
  - Specify width and height in **Pixels (px)**, **Centimeters (cm)**, **Millimeters (mm)**, or **Inches (in)**.
  - Aspect ratio lock ensures photos are never stretched or distorted.
- **Live Before & After Comparison**: View original vs. compressed file size and pixel resolution in real time before saving.

---

### 6. 📑 Smart PDF Resizer & Compressor (Real-Time Preview & Exam Presets)
Matching the power of the Image Resizer, the PDF Resizer brings hardware-accelerated, real-time compression to multi-page documents:
- **Real-Time Visual Page Preview**: Uses Android's native, hardware-accelerated `PdfRenderer` to display crisp previews of document pages with multi-page navigation (`< Page X of Y >`).
- **Live Before & After Stats**: Real-time calculation shows original size, estimated compressed size, and percentage reduction as you type or pick presets.
- **Government Exam & Portal Presets**: One-tap quick presets for **100 KB** (UPSC / SSC / Central Govt), **200 KB** (State PSC & PAN NSDL/UTI), **500 KB** (Resumes & Job Portals), **1 MB** (Email & Web Uploads), and **2 MB** (Contracts & Legal Briefs).
- **Quality & DPI Presets**: Choose between **Light (200 DPI)** for legal archives, **Balanced (150 DPI)** for standard distribution, and **Strong (100 DPI)** for aggressive compression.
- **Vector & Formatting Safety**: Preserves form fields, hyperlinks, and vector text while compressing heavy embedded scanned imagery.
- **Optimized for Low-End Devices**: Renders previews using `Bitmap.Config.RGB_565` (50% RAM savings) clamped to 1080px to avoid memory spikes on budget hardware.

---

### 7. 🖨️ Export Studio & Android Print System
- **PDF Engine**: Generates high-fidelity PDF documents in standard **A4**, **US Letter**, or **Legal** sizes with customizable margins and DPI quality.
- **Direct Android Print Service**: Wirelessly send documents straight to any home or office printer (Wi-Fi, Bluetooth, USB OTG, Mopria, HP, Canon, Epson, Brother) with page selection and color controls.
- **Image Exports**: Save individual pages as compressed JPEG or lossless PNG, or export all pages as a **ZIP archive**.

---

### 7. 📁 Recent Projects & Offline Document Manager
- **Unified Document Hub**: Browse all past scans, passport sheets, and resized images in one clean gallery.
- **High-Performance Gallery**: Employs an in-memory `LruCache` thumbnail loader that eliminates disk I/O during scrolling for 60 FPS performance.
- **Category Filter**: One-tap toggle between "All", "PDF Documents", and "Images".
- **One-Tap Actions**: Share via WhatsApp/Gmail/Drive, open in external viewers (Adobe Acrobat, etc.), print, or safely delete.

---

## ⚡ Lower-End Device Optimizations

Offline Document Composer is engineered to run smoothly on budget devices with 2GB–3GB RAM:
1. **50% RAM Savings with RGB_565**: Image decodes utilize `Bitmap.Config.RGB_565` (2 bytes/pixel vs 4 bytes/pixel), preventing Out-Of-Memory (OOM) crashes on large 48MP photos.
2. **Native Frame Analysis**: Eliminated manual byte loops in the camera analyzer; live frame conversion runs via native AndroidX routines with a balanced 280ms throttle (~3.5 FPS) to keep CPU thermals cool.
3. **Asynchronous File I/O**: All disk queries, file deletions, and thumbnail decodes run strictly on `Dispatchers.IO` coroutines, guaranteeing zero Compose UI freezing.


---

## 📋 Technology Stack & Architecture

- **UI**: 100% Jetpack Compose with Material 3 Design
- **Architecture**: MVVM (Model-View-ViewModel) + Clean Architecture + Kotlin Coroutines & StateFlow
- **Computer Vision**: OpenCV 4.10.0 (Native C++ JNI)
- **PDF Generation**: PDFBox-Android 2.0.27
- **Image Processing**: AndroidX Camera2 & Hardware-accelerated Bitmap transformations
- **Build System**: Gradle 8.9 with Android Gradle Plugin 8.2.0

---

## 📄 License & Attribution

This project is licensed under the [MIT License](LICENSE).  
Third-party libraries used:
- OpenCV (Apache 2.0)
- PDFBox-Android (Apache 2.0)
- AndroidX Jetpack libraries (Apache 2.0)
