import os
import pptx
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE

def create_deck(output_path="Offline_Document_Composer_User_Manual.pptx"):
    prs = pptx.Presentation()
    # 16:9 widescreen
    prs.slide_width = Inches(13.333)
    prs.slide_height = Inches(7.5)
    blank_layout = prs.slide_layouts[6] # completely blank layout

    # Color Palette
    C_BG_DARK = RGBColor(15, 23, 42)      # Slate 900
    C_BG_CARD = RGBColor(30, 41, 59)      # Slate 800
    C_CARD_BORDER = RGBColor(51, 65, 85)  # Slate 700
    C_TEXT_WHITE = RGBColor(248, 250, 252)# Slate 50
    C_TEXT_MUTED = RGBColor(148, 163, 184)# Slate 400
    C_PRIMARY_BLUE = RGBColor(59, 130, 246) # Blue 500
    C_ACCENT_GREEN = RGBColor(16, 185, 129) # Emerald 500
    C_ACCENT_AMBER = RGBColor(245, 158, 11) # Amber 500
    C_ACCENT_PURPLE = RGBColor(168, 85, 247)# Purple 500

    def add_slide_background(slide):
        bg = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, Inches(13.333), Inches(7.5))
        bg.fill.solid()
        bg.fill.fore_color.rgb = C_BG_DARK
        bg.line.fill.background()
        return bg

    def add_header(slide, badge_text, title_text, subtitle_text=None, badge_color=C_PRIMARY_BLUE):
        # Badge pill
        badge_box = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(0.8), Inches(0.4), Inches(2.8), Inches(0.38))
        badge_box.fill.solid()
        badge_box.fill.fore_color.rgb = badge_color
        badge_box.line.fill.background()
        tf_b = badge_box.text_frame
        tf_b.word_wrap = True
        p_b = tf_b.paragraphs[0]
        p_b.text = badge_text.upper()
        p_b.font.size = Pt(11)
        p_b.font.bold = True
        p_b.font.color.rgb = C_TEXT_WHITE
        p_b.alignment = PP_ALIGN.CENTER

        # Title
        title_box = slide.shapes.add_textbox(Inches(0.8), Inches(0.85), Inches(11.7), Inches(0.65))
        tf_t = title_box.text_frame
        tf_t.word_wrap = True
        p_t = tf_t.paragraphs[0]
        p_t.text = title_text
        p_t.font.size = Pt(24)
        p_t.font.bold = True
        p_t.font.color.rgb = C_TEXT_WHITE

        if subtitle_text:
            p_sub = tf_t.add_paragraph()
            p_sub.text = subtitle_text
            p_sub.font.size = Pt(13)
            p_sub.font.color.rgb = C_TEXT_MUTED
            p_sub.space_before = Pt(4)

    def add_card(slide, left, top, width, height, title, items, badge="", accent=C_PRIMARY_BLUE):
        card = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(left), Inches(top), Inches(width), Inches(height))
        card.fill.solid()
        card.fill.fore_color.rgb = C_BG_CARD
        card.line.color.rgb = C_CARD_BORDER
        card.line.width = Pt(1.5)

        tf = card.text_frame
        tf.word_wrap = True
        tf.margin_left = Inches(0.25)
        tf.margin_right = Inches(0.25)
        tf.margin_top = Inches(0.22)
        tf.margin_bottom = Inches(0.2)

        # Header in card
        p0 = tf.paragraphs[0]
        p0.text = f"{badge}  {title}".strip()
        p0.font.size = Pt(16)
        p0.font.bold = True
        p0.font.color.rgb = accent
        p0.space_after = Pt(8)

        # Items
        for item in items:
            p = tf.add_paragraph()
            if isinstance(item, tuple):
                bold_part, regular_part = item
                r1 = p.add_run()
                r1.text = "• " + bold_part + ": "
                r1.font.bold = True
                r1.font.size = Pt(12)
                r1.font.color.rgb = C_TEXT_WHITE
                r2 = p.add_run()
                r2.text = regular_part
                r2.font.bold = False
                r2.font.size = Pt(12)
                r2.font.color.rgb = C_TEXT_MUTED
            else:
                p.text = "• " + item
                p.font.size = Pt(12)
                p.font.color.rgb = C_TEXT_MUTED
            p.space_before = Pt(4)
        return card

    # =========================================================================
    # SLIDE 1: Title Slide
    # =========================================================================
    s1 = prs.slides.add_slide(blank_layout)
    add_slide_background(s1)

    # Decorative Accent Bar
    bar = s1.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.8), Inches(1.5), Inches(0.15), Inches(3.8))
    bar.fill.solid()
    bar.fill.fore_color.rgb = C_PRIMARY_BLUE
    bar.line.fill.background()

    # Title Box
    t_box = s1.shapes.add_textbox(Inches(1.2), Inches(1.4), Inches(11.0), Inches(3.8))
    tf1 = t_box.text_frame
    tf1.word_wrap = True

    p = tf1.paragraphs[0]
    p.text = "OFFLINE DOCUMENT COMPOSER"
    p.font.size = Pt(36)
    p.font.bold = True
    p.font.color.rgb = C_TEXT_WHITE
    p.space_after = Pt(8)

    p2 = tf1.add_paragraph()
    p2.text = "Comprehensive User Manual & Feature Operations Guide"
    p2.font.size = Pt(20)
    p2.font.bold = True
    p2.font.color.rgb = C_PRIMARY_BLUE
    p2.space_after = Pt(14)

    p3 = tf1.add_paragraph()
    p3.text = "100% Private & Offline • Hardware-Accelerated Scanner • Biometric Passport Maker • Smart Image Resizer"
    p3.font.size = Pt(13)
    p3.font.color.rgb = C_TEXT_MUTED

    # Highlights Pills at bottom
    badges = [
        ("🔒 100% Offline & Private", C_ACCENT_GREEN, 1.2),
        ("⚡ 60 FPS Performance", C_PRIMARY_BLUE, 4.2),
        ("📐 Indian Passport Biometrics", C_ACCENT_AMBER, 7.0),
        ("📦 Ultra-Lean 49MB APK", C_ACCENT_PURPLE, 10.3),
    ]
    for text, col, left in badges:
        pill = s1.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(left), Inches(5.8), Inches(2.7), Inches(0.55))
        pill.fill.solid()
        pill.fill.fore_color.rgb = C_BG_CARD
        pill.line.color.rgb = col
        pill.line.width = Pt(1.5)
        tf_pill = pill.text_frame
        tf_pill.word_wrap = True
        p_pill = tf_pill.paragraphs[0]
        p_pill.text = text
        p_pill.font.size = Pt(11)
        p_pill.font.bold = True
        p_pill.font.color.rgb = C_TEXT_WHITE
        p_pill.alignment = PP_ALIGN.CENTER

    # =========================================================================
    # SLIDE 2: Core Philosophy & Key Value Pillars
    # =========================================================================
    s2 = prs.slides.add_slide(blank_layout)
    add_slide_background(s2)
    add_header(s2, "Overview & Vision", "Why Offline Document Composer?", "Engineered from the ground up for privacy, reliability, and extreme speed on all Android phones.")

    add_card(s2, 0.8, 1.9, 3.7, 4.9, "100% Offline & Private", [
        ("Zero Cloud Uploads", "Your private ID cards, financial records, and photos never touch external servers or the internet."),
        ("No Account Needed", "Instant access without phone numbers, logins, or unnecessary data collection."),
        ("Total Device Safety", "All document processing runs strictly in local CPU/GPU memory."),
        ("No Internet Permission", "Guaranteed offline security by Android operating system permissions.")
    ], badge="🔒", accent=C_ACCENT_GREEN)

    add_card(s2, 4.8, 1.9, 3.7, 4.9, "Engineered for Performance", [
        ("Lower-End Device Optimized", "Tested and tuned for budget Android phones with 2GB–4GB RAM."),
        ("50% RAM Savings", "Smart RGB_565 memory pipeline avoids Out-Of-Memory (OOM) crashes."),
        ("Instant Slider Response", "3ms preview updates while adjusting contrast/brightness without UI freeze."),
        ("70% Smaller App", "Stripped 96MB of dead PC emulator binaries down to a lean 49MB.")
    ], badge="⚡", accent=C_PRIMARY_BLUE)

    add_card(s2, 8.8, 1.9, 3.7, 4.9, "All-in-One Powerhouse", [
        ("Smart OpenCV Scanner", "Live camera perspective warping and automatic document boundary snap."),
        ("Biometric Passport Maker", "Indian & global passport/visa standards with multi-copy print sheet tiling."),
        ("Govt Exam Resizer", "Compress photos and signatures to exact KB limits (e.g. <50KB, <20KB)."),
        ("Print & Vector PDF", "Direct Android Print Service integration for Wi-Fi & USB printing.")
    ], badge="🛠️", accent=C_ACCENT_AMBER)

    # =========================================================================
    # SLIDE 3: Complete App Architecture & Navigation
    # =========================================================================
    s3 = prs.slides.add_slide(blank_layout)
    add_slide_background(s3)
    add_header(s3, "Architecture", "Complete Module Architecture", "Six specialized modules designed for seamless document creation and processing.")

    modules = [
        ("1. Live Smart Scanner", "Real-time edge detection, auto-capture, torch control, and perspective warp.", "📷", 0.8, 1.9),
        ("2. Document Editor", "Document Crisp B&W, Magic Color, contrast/brightness sliders, and rotation.", "🎨", 4.8, 1.9),
        ("3. Page Composer", "Multi-page canvas, drag & drop reorder, watermarks, text annotations & signature.", "📄", 8.8, 1.9),
        ("4. Passport Photo Studio", "Biometric face guidelines, Indian 35x45mm, multi-copy grid tiling & cutting borders.", "👤", 0.8, 4.4),
        ("5. Smart Image Resizer", "Resize by dimensions (px/cm/mm/in) or compress to target file size (<50KB for exams).", "📐", 4.8, 4.4),
        ("6. Export & Recent Projects", "High-res PDF generation, direct Android Print adapter, and LRU-cached gallery.", "📁", 8.8, 4.4),
    ]

    for title, desc, icon, left, top in modules:
        card = s3.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(left), Inches(top), Inches(3.7), Inches(2.2))
        card.fill.solid()
        card.fill.fore_color.rgb = C_BG_CARD
        card.line.color.rgb = C_CARD_BORDER
        card.line.width = Pt(1.5)
        tf = card.text_frame
        tf.word_wrap = True
        tf.margin_left = Inches(0.2)
        tf.margin_right = Inches(0.2)
        tf.margin_top = Inches(0.18)

        p = tf.paragraphs[0]
        p.text = f"{icon}  {title}"
        p.font.size = Pt(15)
        p.font.bold = True
        p.font.color.rgb = C_PRIMARY_BLUE
        p.space_after = Pt(6)

        p_desc = tf.add_paragraph()
        p_desc.text = desc
        p_desc.font.size = Pt(12)
        p_desc.font.color.rgb = C_TEXT_MUTED

    # =========================================================================
    # SLIDE 4: Module 1 — Live Smart Camera & Document Scanner
    # =========================================================================
    s4 = prs.slides.add_slide(blank_layout)
    add_slide_background(s4)
    add_header(s4, "Module 1 Guide", "Smart Camera & Live Document Scanner", "Turn your camera into a professional flatbed scanner with real-time OpenCV detection.")

    add_card(s4, 0.8, 1.9, 5.7, 4.9, "How to Scan Documents", [
        ("Step 1: Frame the Document", "Hold your phone above the paper, receipt, or ID card. Ensure even lighting."),
        ("Step 2: Real-Time Edge Tracking", "The live OpenCV engine automatically highlights document corners with a green polygon."),
        ("Step 3: Auto or Manual Capture", "Tap the shutter button or enable auto-capture when corners stabilize."),
        ("Step 4: Fine-Tune with Loupe", "On the crop screen, touch any corner to activate the 2.5x Loupe Magnifier for millimeter precision."),
        ("Step 5: Perspective Correction", "The app warps angled shots into a perfectly flat, rectangular top-down document.")
    ], badge="📷", accent=C_PRIMARY_BLUE)

    add_card(s4, 6.8, 1.9, 5.7, 4.9, "Pro Scanner Controls", [
        ("Flashlight Toggle", "Tap the flash icon in dark rooms to eliminate harsh shadows."),
        ("Batch Capture Mode", "Snap multiple pages in sequence without leaving the camera viewfinder."),
        ("Contrasting Backgrounds", "Place white paper on a dark desk for instant 0.1-second edge snapping."),
        ("High-Speed Native Engine", "Camera analysis runs on a background native thread, ensuring zero viewfinder lag."),
        ("Direct Gallery Import", "Tap the photo icon to import existing receipts or documents from your gallery.")
    ], badge="💡", accent=C_ACCENT_AMBER)

    # =========================================================================
    # SLIDE 5: Module 2 — Document Enhancement & Filter Engine
    # =========================================================================
    s5 = prs.slides.add_slide(blank_layout)
    add_slide_background(s5)
    add_header(s5, "Module 2 Guide", "Document Enhancement & Filter Engine", "Make scanned pages look crystal clear, high-contrast, and clean for printing.")

    add_card(s5, 0.8, 1.9, 5.7, 4.9, "Color Filter Presets", [
        ("Original", "Preserves exact camera colors, ideal for vibrant art, paintings, and color brochures."),
        ("Document Crisp (B&W)", "Turns dingy gray paper pure white and texts pitch black. Best for official forms."),
        ("Grayscale", "Smooth 256-level neutral tones, perfect for historical documents and pencil sketches."),
        ("Magic Color", "Enhances saturation, sharpness, and clarity while preserving color stamps and signatures."),
        ("High Contrast", "Maximizes text readability on faint carbon copies or faded thermal receipts.")
    ], badge="🎨", accent=C_PRIMARY_BLUE)

    add_card(s5, 6.8, 1.9, 5.7, 4.9, "Fine-Tuning & Zero-Lag Sliders", [
        ("Brightness & Contrast", "Easily remove shadows or recover over-exposed scans with intuitive slider controls."),
        ("Sharpness Enhancement", "Recovers text crispness on lower-end camera sensors and blurry photos."),
        ("3ms Instant Preview", "Sliders update a lightweight 1080px preview canvas instantly with zero UI stutter."),
        ("90° Quick Rotation", "Rotate pages left or right with a single tap to correct document orientation."),
        ("Full-Res Final Processing", "Original 12MP–48MP images are only processed when tapping Apply/Save.")
    ], badge="⚡", accent=C_ACCENT_GREEN)

    # =========================================================================
    # SLIDE 6: Module 3 — Page Composer & Multi-Layer Studio
    # =========================================================================
    s6 = prs.slides.add_slide(blank_layout)
    add_slide_background(s6)
    add_header(s6, "Module 3 Guide", "Page Composer & Multi-Layer Studio", "Assemble multi-page PDFs, reorder pages, add watermarks, annotations, and digital signatures.")

    add_card(s6, 0.8, 1.9, 5.7, 4.9, "Multi-Page Document Assembly", [
        ("Drag-and-Drop Reordering", "Long-press any thumbnail and drag to rearrange pages in exact reading order."),
        ("Add & Duplicate Pages", "Add blank sheets, import additional gallery images, or duplicate existing pages."),
        ("Individual Page Cropping", "Re-enter the crop or filter studio for any single page at any time."),
        ("Delete & Prune", "Remove mis-scanned or duplicate sheets with one tap.")
    ], badge="📄", accent=C_PRIMARY_BLUE)

    add_card(s6, 6.8, 1.9, 5.7, 4.9, "Canvas Overlay Studio", [
        ("Digital Signature", "Draw your signature directly on screen and place it anywhere on the page."),
        ("Official Watermarks", "Add 'CONFIDENTIAL', 'SAMPLE', or custom text across pages with adjustable opacity."),
        ("Text Annotations", "Type notes, dates, or form fields with custom fonts, colors, and sizing."),
        ("Multi-Layer Gestures", "Pinch to scale, drag to reposition, and rotate any layer with two fingers."),
        ("Undo / Redo History", "Complete safety stack allows reversing any accidental modifications.")
    ], badge="✏️", accent=C_ACCENT_PURPLE)

    # =========================================================================
    # SLIDE 7: Module 4 — Passport Size Photo Maker
    # =========================================================================
    s7 = prs.slides.add_slide(blank_layout)
    add_slide_background(s7)
    add_header(s7, "Module 4 Guide", "Passport Size Photo Maker", "Create professional, biometric passport and visa photos aligned to official Indian & global standards.")

    add_card(s7, 0.8, 1.9, 5.7, 4.9, "Standard Biometric Sizes", [
        ("Indian Passport / Visa", "Official 35 × 45 mm (3.5 × 4.5 cm) standard for Indian Passport, OCI, and Visas."),
        ("PAN Card Form", "Standard 25 × 35 mm (2.5 × 3.5 cm) for NSDL and UTI official PAN applications."),
        ("Official Stamp Size", "Standard 20 × 25 mm format for ID cards, service registers, and employee passes."),
        ("US Visa / Green Card", "Square 2 × 2 inches (51 × 51 mm) for US Visa, DV Lottery, and global permits."),
        ("Custom Size Option", "Freely enter any width and height in millimeters.")
    ], badge="📐", accent=C_ACCENT_AMBER)

    add_card(s7, 6.8, 1.9, 5.7, 4.9, "Smart Multi-Copy Grid Tiling", [
        ("Face Guide Overlay", "Biometric oval guide aligns chin, crown, and eyes to match official guidelines."),
        ("Copy Count Selection", "Generate 1, 2, 4, 6, 8, 12, 16, or 32 photos automatically aligned on a single sheet."),
        ("Standard Print Sheets", "Optimized for 4×6 inch photo paper or standard A4 sheets for studio printing."),
        ("Cutting Border Lines", "Includes subtle border guidelines for quick, straight scissor or paper cutter trimming."),
        ("Export Options", "Save as high-res JPG, 300 DPI PDF, or print directly via connected printer.")
    ], badge="👤", accent=C_ACCENT_GREEN)

    # =========================================================================
    # SLIDE 8: Module 5 — Smart Image Resizer & File Size Compressor
    # =========================================================================
    s8 = prs.slides.add_slide(blank_layout)
    add_slide_background(s8)
    add_header(s8, "Module 5 Guide", "Smart Image Resizer & File Size Compressor", "Effortlessly meet strict online application portal requirements for exams, jobs, and government portals.")

    add_card(s8, 0.8, 1.9, 5.7, 4.9, "Target File Size Compression", [
        ("The Problem It Solves", "Government portals (UPSC, SSC, State PSC, IBPS) reject files over 50 KB or 20 KB."),
        ("Exact KB Target Mode", "Simply type your target (e.g., '50' or '20' KB) and tap Compress."),
        ("Iterative Binary Search", "Algorithm finds the exact compression quality that meets the size limit with maximum clarity."),
        ("Signature Mode", "Specially tuned to keep black ink signature strokes sharp even under 10 KB file sizes.")
    ], badge="🎯", accent=C_ACCENT_GREEN)

    add_card(s8, 6.8, 1.9, 5.7, 4.9, "Dimension Scaling & Quality Control", [
        ("Multi-Unit Support", "Resize by Pixels (px), Centimeters (cm), Millimeters (mm), or Inches (in)."),
        ("Aspect Ratio Lock", "Prevent stretching or squishing when changing width or height."),
        ("Manual Quality Slider", "Fine-tune JPEG compression level from 1% to 100% with live size recalculation."),
        ("Before & After Inspector", "Live preview shows exact original vs. output file size and pixel dimensions."),
        ("Batch Support", "Resize multiple certificates or document pages in a single operation.")
    ], badge="📏", accent=C_PRIMARY_BLUE)

    # =========================================================================
    # SLIDE 9: Module 6 — Export Studio & Android Print System
    # =========================================================================
    s9 = prs.slides.add_slide(blank_layout)
    add_slide_background(s9)
    add_header(s9, "Module 6 Guide", "Export Studio & Android Print System", "Export in crisp formats or send documents directly to your home/office printer.")

    add_card(s9, 0.8, 1.9, 5.7, 4.9, "Export Formats & PDF Engine", [
        ("Standard PDF Export", "Creates vector-compatible, multi-page PDF documents readable on all devices."),
        ("Page Format Presets", "Choose standard A4, US Letter, Legal, or match original image dimensions."),
        ("Image Formats (JPG / PNG)", "Export individual pages as compressed JPG or lossless PNG images."),
        ("Batch ZIP Archive", "Package all document pages into a single ZIP file for easy email or transfer."),
        ("DPI Resolution Control", "Select standard 150 DPI for compact files or 300 DPI for archival quality.")
    ], badge="📄", accent=C_PRIMARY_BLUE)

    add_card(s9, 6.8, 1.9, 5.7, 4.9, "Direct Android Print Integration", [
        ("Native Print Adapter", "Uses Android PrintManager to connect directly with Wi-Fi, USB, and network printers."),
        ("Print Preview", "View real-time layout before paper is consumed."),
        ("Mopria, HP, Canon, Epson", "Fully compatible with standard Android print plugins and vendor drivers."),
        ("Save as PDF via Print Spooler", "Alternative system-level PDF export with page range selection."),
        ("Zero Cloud Drivers", "Print data stays strictly within your local local network without external cloud routing.")
    ], badge="🖨️", accent=C_ACCENT_PURPLE)

    # =========================================================================
    # SLIDE 10: Module 7 — Recent Projects & Document Manager
    # =========================================================================
    s10 = prs.slides.add_slide(blank_layout)
    add_slide_background(s10)
    add_header(s10, "Module 7 Guide", "Recent Projects & Document Manager", "Access, search, share, and manage all your exported PDFs, photos, and scans.")

    add_card(s10, 0.8, 1.9, 5.7, 4.9, "Instant Gallery & Filter Bar", [
        ("Unified Project List", "Access all generated documents, passport grids, and resized photos in one screen."),
        ("Category Filter Bar", "Instantly filter between 'All', 'PDF Documents', and 'Images'."),
        ("File Metadata Details", "Displays exact file size (KB/MB), last modified timestamp, and format badge."),
        ("LRU Thumbnail Cache", "Thumbnails load instantly from memory cache, ensuring silky smooth scrolling.")
    ], badge="📁", accent=C_PRIMARY_BLUE)

    add_card(s10, 6.8, 1.9, 5.7, 4.9, "Document Operations & Storage", [
        ("Native Share Sheet", "One-tap sharing via WhatsApp, Gmail, Telegram, Bluetooth, or Google Drive."),
        ("Open in External Viewer", "Open PDFs in Adobe Acrobat or images in your preferred gallery app."),
        ("Direct Print from List", "Tap the 3-dot menu on any project card to print immediately."),
        ("Standard Storage Locations", "Files are stored in accessible device folders: `Documents/DocComposer/` and `Pictures/ResizedImages/`."),
        ("Safe Deletion", "Confirm prompt prevents accidental deletion of important documents.")
    ], badge="⚙️", accent=C_ACCENT_AMBER)

    # =========================================================================
    # SLIDE 11: Under the Hood — Performance & Low-End Optimizations
    # =========================================================================
    s11 = prs.slides.add_slide(blank_layout)
    add_slide_background(s11)
    add_header(s11, "Engineering", "Performance & Low-End Device Optimizations", "How Offline Document Composer runs butter-smooth even on 2GB RAM budget phones.")

    add_card(s11, 0.8, 1.9, 3.7, 4.9, "Memory Optimization", [
        ("RGB_565 Decodes", "Switched preview bitmaps from 4 bytes/px (ARGB_8888) to 2 bytes/px (RGB_565), cutting memory use by 50%."),
        ("Dynamic Heap Scaling", "Downsampling automatically adapts based on device total heap (`Runtime.maxMemory()`)."),
        ("Zero Out-of-Memory", "Prevents memory exhaustion crashes when processing 48MP camera photos."),
        ("LRU Thumbnail Cache", "Caches small thumbnails in RAM to avoid redundant disk decodes during list scrolling.")
    ], badge="🧠", accent=C_ACCENT_GREEN)

    add_card(s11, 4.8, 1.9, 3.7, 4.9, "CPU & Frame Rate", [
        ("Native Frame Analyzer", "Replaced 300,000-iteration manual loops with hardware-accelerated AndroidX conversion."),
        ("Optimized Frame Throttle", "280ms analyzer throttle gives responsive edge detection without overheating budget CPUs."),
        ("Streamlined OpenCV", "Single 7.0 Gaussian kernel for live preview; heavy Hough transforms reserved for static shots."),
        ("Background Coroutines", "All disk scans and image operations run on `Dispatchers.IO`.")
    ], badge="⚡", accent=C_PRIMARY_BLUE)

    add_card(s11, 8.8, 1.9, 3.7, 4.9, "APK Size Optimization", [
        ("Stripped 96MB Intel So", "Configured `abiFilters` to eliminate unused x86 PC emulator binaries from OpenCV."),
        ("R8 / Proguard Trimming", "Dead code elimination prunes unused BouncyCastle cryptography from PDFBox."),
        ("Shrink Resources", "Removes unused XML and drawable assets from release builds."),
        ("70% Size Drop", "Debug APK dropped from 163MB to 62MB; Minified Release APK is just 49MB!")
    ], badge="📦", accent=C_ACCENT_PURPLE)

    # =========================================================================
    # SLIDE 12: Step-by-Step Quick Start Walkthrough
    # =========================================================================
    s12 = prs.slides.add_slide(blank_layout)
    add_slide_background(s12)
    add_header(s12, "Quick Start", "4-Step Quick Start Workflow", "From physical paper to professional PDF in under 30 seconds.")

    steps = [
        ("Step 1: Capture / Import", "Open Smart Scanner. Align document corners within the green overlay and tap the shutter button, or import photos from your gallery.", "📷", 0.8),
        ("Step 2: Enhance & Filter", "Select 'Document Crisp' filter to eliminate shadows and background tint. Adjust brightness or contrast sliders if needed.", "✨", 3.8),
        ("Step 3: Arrange & Sign", "In Page Composer, drag pages into the desired order. Add a digital signature or date stamp using the overlay studio.", "✏️", 6.8),
        ("Step 4: Export & Share", "Choose A4 PDF format. Tap Export to save locally, share via WhatsApp/Gmail, or send straight to a Wi-Fi printer.", "🚀", 9.8),
    ]

    for title, desc, icon, left in steps:
        card = s12.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(left), Inches(1.9), Inches(2.7), Inches(4.9))
        card.fill.solid()
        card.fill.fore_color.rgb = C_BG_CARD
        card.line.color.rgb = C_PRIMARY_BLUE
        card.line.width = Pt(1.5)
        tf = card.text_frame
        tf.word_wrap = True
        tf.margin_left = Inches(0.2)
        tf.margin_right = Inches(0.2)
        tf.margin_top = Inches(0.25)

        p = tf.paragraphs[0]
        p.text = f"{icon}  {title}"
        p.font.size = Pt(16)
        p.font.bold = True
        p.font.color.rgb = C_TEXT_WHITE
        p.space_after = Pt(12)

        p_desc = tf.add_paragraph()
        p_desc.text = desc
        p_desc.font.size = Pt(13)
        p_desc.font.color.rgb = C_TEXT_MUTED
        p_desc.space_after = Pt(8)

    # =========================================================================
    # SLIDE 13: Frequently Asked Questions (FAQ) & Troubleshooting
    # =========================================================================
    s13 = prs.slides.add_slide(blank_layout)
    add_slide_background(s13)
    add_header(s13, "FAQ & Help", "Frequently Asked Questions & Troubleshooting", "Common questions, file locations, and helpful tips.")

    add_card(s13, 0.8, 1.9, 5.7, 4.9, "Frequently Asked Questions", [
        ("Does this app require internet?", "No. 100% offline. No internet permission is even requested in AndroidManifest."),
        ("Where are my files stored?", "In your device's internal storage under `Documents/DocComposer/` and `Pictures/ResizedImages/`."),
        ("Can I transfer files to my PC?", "Yes! Simply plug your phone into your computer via USB and open the Documents folder."),
        ("Will it work without Google Play Services?", "Yes! It is fully self-contained without dependencies on GMS or Firebase.")
    ], badge="❓", accent=C_PRIMARY_BLUE)

    add_card(s13, 6.8, 1.9, 5.7, 4.9, "Troubleshooting Tips", [
        ("Document corners not snapping?", "Place the document on a contrasting surface (e.g., white paper on a dark wooden table) and avoid heavy shadows."),
        ("Image Resizer target size not reached?", "If an image is extremely complex (dense text/graphics), lower the pixel dimensions slightly before applying JPEG compression."),
        ("Printer not showing up?", "Ensure your printer and phone are on the exact same Wi-Fi network and your printer's Android plugin (Mopria/HP/Canon) is installed."),
        ("Need to free up storage?", "Open Recent Projects screen and tap the trash icon on older exported files you no longer need.")
    ], badge="🛠️", accent=C_ACCENT_AMBER)

    # =========================================================================
    # SLIDE 14: Publishing & Technical Specifications
    # =========================================================================
    s14 = prs.slides.add_slide(blank_layout)
    add_slide_background(s14)
    add_header(s14, "Tech Specs", "Technical Specifications & Developer Stack", "Built with modern Android engineering standards and Jetpack Compose.")

    add_card(s14, 0.8, 1.9, 5.7, 4.9, "Technical Stack", [
        ("Language & Runtime", "100% Modern Kotlin 1.9.21 targeting JVM 17."),
        ("UI Framework", "Jetpack Compose with Material 3 Design System."),
        ("Computer Vision Engine", "OpenCV 4.10.0 Native C++ JNI implementation."),
        ("PDF Creation", "PDFBox-Android 2.0.27 with native vector PDF generation."),
        ("Architecture", "MVVM (Model-View-ViewModel) + Clean Architecture + StateFlow."),
        ("Supported Android Versions", "Android 8.0 (API 26) up to Android 14+ (API 34).")
    ], badge="💻", accent=C_PRIMARY_BLUE)

    add_card(s14, 6.8, 1.9, 5.7, 4.9, "Release & Store Readiness", [
        ("Play Store Compliant", "Targeting API 34 (Android 14) conforming to Google Play guidelines."),
        ("Data Safety Declaration", "Zero data collected, zero data shared with third parties."),
        ("App Bundle (.aab)", "Ready for `bundleRelease` generation for optimized per-device delivery."),
        ("R8 Minified", "Full Proguard obfuscation, dead code pruning, and resource shrinking enabled."),
        ("Offline Security Badge", "Ideal for enterprises, students, professionals, and privacy advocates.")
    ], badge="🚀", accent=C_ACCENT_GREEN)

    # Save
    prs.save(output_path)
    print(f"Presentation saved successfully to: {output_path}")

if __name__ == "__main__":
    create_deck()
