package com.example.offlinedocumentcomposer.domain.detector

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.example.offlinedocumentcomposer.opencv.OpenCvUtils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

enum class DetectionMode { DOCUMENT, ID_CARD }
enum class DetectionStatus { DETECTED, NEEDS_ADJUSTMENT, FAILED }

class DocumentDetector {
    private val allocationScope = ThreadLocal<MutableList<Mat>>()
    private fun <T : Mat> hold(mat: T): T { allocationScope.get()?.add(mat); return mat }


    companion object {
        private const val TAG = "DocumentDetector"
        const val ID_CARD_ASPECT_RATIO = 85.60f / 53.98f // Standard ISO/IEC 7810 ID-1: ~1.5858
        private const val BORDER_PAD = 16 // Replicated border padding
    }

    /**
     * Detects document corners using a multi-pass boundary pipeline.
     * Gathers candidate quadrilaterals across all passes and scores them based on:
     * 1. Perimeter Edge Alignment (S_edge, 40%) - checks real Canny edges on all 4 sides
     * 2. Internal Text & Content Density (S_content, 25%) - eliminates blank floors/tables
     * 3. Geometric Shape & Ratio (S_geom, 35%) - rectangularity and ID-1 ratio affinity
     */
    fun detect(bitmap: Bitmap, mode: DetectionMode = DetectionMode.DOCUMENT, live: Boolean = false): DetectionResult {
        val resources = mutableListOf<Mat>()
        var processingBitmap: Bitmap? = null
        if (!OpenCvUtils.initOpenCV()) {
            Log.w(TAG, "OpenCV unavailable; manual crop required")
            return DetectionResult(
                success = false,
                corners = getFullImageCorners(bitmap.width, bitmap.height),
                confidence = 0f,
                message = "Automatic detection unavailable. Adjust corners.",
                status = DetectionStatus.FAILED
            )
        }

        allocationScope.set(resources)
        return try {
            val originalW = bitmap.width
            val originalH = bitmap.height

            // 1. Downsample for fast & robust processing (target max dimension ~1000px)
            val maxDim = if (live) 640.0 else 1000.0
            val scale = if (max(originalW, originalH) > maxDim) {
                maxDim / max(originalW, originalH).toDouble()
            } else {
                1.0
            }

            val procW = (originalW * scale).toInt().coerceAtLeast(1)
            val procH = (originalH * scale).toInt().coerceAtLeast(1)
            val smallBmp = Bitmap.createScaledBitmap(bitmap, procW, procH, true)
            processingBitmap = smallBmp

            val srcMat = OpenCvUtils.bitmapToMat(smallBmp).also { resources.add(it) }

            // Replicate border padding so documents touching or close to the photo boundary form closed contours
            val paddedSrc = hold(Mat()).also { resources.add(it) }
            Core.copyMakeBorder(
                srcMat,
                paddedSrc,
                BORDER_PAD, BORDER_PAD, BORDER_PAD, BORDER_PAD,
                Core.BORDER_REPLICATE
            )

            val totalArea = procW.toDouble() * procH.toDouble()
            val paddedW = paddedSrc.cols().toDouble()
            val paddedH = paddedSrc.rows().toDouble()

            val grayMat = hold(Mat()).also { resources.add(it) }
            Imgproc.cvtColor(paddedSrc, grayMat, Imgproc.COLOR_RGBA2GRAY)

            val blurred = hold(Mat()).also { resources.add(it) }
            Imgproc.GaussianBlur(grayMat, blurred, Size(3.0, 3.0), 0.0)

            // Do not remove boundaries based on skin color. Cards and backgrounds overlap that range.
            val skinMask = Mat.zeros(paddedSrc.size(), CvType.CV_8UC1).also { resources.add(it) }

            // Master Edge Maps for boundary alignment and internal content scoring
            val cannyEdges = hold(Mat()).also { resources.add(it) }
            Imgproc.Canny(blurred, cannyEdges, 20.0, 65.0)
            // A colored boundary can be clear in RGB but disappear in grayscale.
            // Use the same evidence for candidate scoring as for contour extraction.
            val channels = mutableListOf<Mat>()
            Core.split(paddedSrc, channels)
            channels.forEach { hold(it) }
            val channelBlur = hold(Mat())
            val channelEdges = hold(Mat())
            for (channel in channels.take(3)) {
                Imgproc.GaussianBlur(channel, channelBlur, Size(3.0, 3.0), 0.0)
                Imgproc.Canny(channelBlur, channelEdges, 20.0, 65.0)
                Core.bitwise_or(cannyEdges, channelEdges, cannyEdges)
            }
            // Subtract skin mask so fingers/hands don't create false document edges
            Core.subtract(cannyEdges, skinMask, cannyEdges)

            val dilatedEdges = hold(Mat()).also { resources.add(it) }
            val edgeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            Imgproc.dilate(cannyEdges, dilatedEdges, edgeKernel)
            edgeKernel.release()

            val allCandidates = mutableListOf<Array<Point>>()
            // Multi-scale dilation and closing to bridge edge gaps (single kernel for live to conserve CPU)
            val ksizes = if (live) doubleArrayOf(7.0) else doubleArrayOf(5.0, 7.0, 9.0)
            for (ksize in ksizes) {
                val k = hold(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(ksize, ksize)))
                val dil = hold(Mat())
                Imgproc.dilate(cannyEdges, dil, k)
                val closed = hold(Mat())
                Imgproc.morphologyEx(dil, closed, Imgproc.MORPH_CLOSE, k)
                allCandidates.addAll(extractCandidateQuads(closed, totalArea, paddedW, paddedH))
                k.release()
                dil.release()
                closed.release()
            }

            val region = hold(Mat()).also { resources.add(it) }
            Imgproc.threshold(blurred, region, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            allCandidates.addAll(extractCandidateQuads(region, totalArea, paddedW, paddedH))
            Imgproc.threshold(blurred, region, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
            allCandidates.addAll(extractCandidateQuads(region, totalArea, paddedW, paddedH))

            // Pass 1: White Card HSV Luminance/Saturation Segmentation (Specialized for ID cards: Aadhaar, PAN, DL)
            if (!live) allCandidates.addAll(runWhiteCardSegmentationPass(paddedSrc, skinMask, totalArea, paddedW, paddedH))

            // Pass 2: Hough Line Segment Intersection (CamScanner straight ID-edge resolution) - heavy pass, only for static captures
            if (!live) allCandidates.addAll(runHoughLinePass(blurred, skinMask, totalArea, paddedW, paddedH))

            // Pass 3: Canny with Otsu-guided threshold + Morphological Close
            allCandidates.addAll(runCannyPass(blurred, skinMask, totalArea, paddedW, paddedH, useOtsu = true))

            // Pass 4: Low-threshold Canny (catches subtle boundaries: white cards on light tables/bedsheets)
            allCandidates.addAll(runCannyPass(blurred, skinMask, totalArea, paddedW, paddedH, useOtsu = false, fixedLow = 20.0, fixedHigh = 75.0))

            // Pass 5: Morphological Gradient (additional candidates under uneven lighting)
            if (!live) allCandidates.addAll(runMorphGradientPass(grayMat, skinMask, totalArea, paddedW, paddedH))

            // Pass 6: Multi-Channel (RGB + Saturation) Edge Combination
            if (!live) allCandidates.addAll(runMultiChannelPass(paddedSrc, skinMask, totalArea, paddedW, paddedH))

            // Pass 7: Adaptive Thresholding
            if (!live) allCandidates.addAll(runAdaptiveThresholdPass(blurred, skinMask, totalArea, paddedW, paddedH))

            // Pass 8: Baseline document frames (properly offset by border padding)
            val defaultDoc = getDefaultCorners(procW, procH).map { Point((it.x + BORDER_PAD).toDouble(), (it.y + BORDER_PAD).toDouble()) }.toTypedArray()
            allCandidates.add(defaultDoc)
            if (mode == DetectionMode.ID_CARD) {
                val idDoc = getIdCardCorners(procW, procH).map { Point((it.x + BORDER_PAD).toDouble(), (it.y + BORDER_PAD).toDouble()) }.toTypedArray()
                allCandidates.add(idDoc)
            }

            Log.d(TAG, "Total candidate quads gathered across all passes: ${allCandidates.size}")

            var bestPts: Array<Point>? = null
            var bestScore = 0f

            for (candidate in allCandidates.distinctBy { pts -> pts.joinToString { "${(it.x / 5).toInt()},${(it.y / 5).toInt()}" } }) {
                val score = scoreCandidate(candidate, dilatedEdges, cannyEdges, totalArea, paddedW, paddedH, mode)
                if (score > 0f) {
                    Log.d(TAG, "Candidate quad scored: $score")
                }
                if (score > bestScore) {
                    bestScore = score
                    bestPts = candidate
                }
            }

            if (bestPts != null && bestScore >= DetectionResult.CONFIDENCE_THRESHOLD) {
                // Remove border padding offset and scale back to original bitmap dimensions
                
                val unpaddedCorners = bestPts.map { pt ->
                    val unpadX = (pt.x - BORDER_PAD)
                    val unpadY = (pt.y - BORDER_PAD)
                    PointF(
                        (unpadX * originalW / procW).toFloat().coerceIn(0f, (originalW - 1).toFloat()),
                        (unpadY * originalH / procH).toFloat().coerceIn(0f, (originalH - 1).toFloat())
                    )
                }
                val proposal = orderCorners(unpaddedCorners)
                require(CropGeometry.valid(proposal, originalW, originalH)) { "Invalid candidate geometry" }
                val ordered = if (live) proposal else BoundaryRefiner.refine(bitmap, proposal)
                Log.i(TAG, "Document successfully detected with confidence $bestScore: $ordered")
                DetectionResult(
                    success = true,
                    corners = ordered,
                    confidence = bestScore,
                    message = "Boundary detected. Check the corners."
                )
            } else {
                val suggestion = bestPts?.takeIf { bestScore >= 0.30f }?.map { point ->
                    PointF(((point.x - BORDER_PAD) * originalW / procW).toFloat().coerceIn(0f, (originalW - 1).toFloat()),
                        ((point.y - BORDER_PAD) * originalH / procH).toFloat().coerceIn(0f, (originalH - 1).toFloat()))
                }?.let { orderCorners(it) }?.takeIf { CropGeometry.valid(it, originalW, originalH) }
                Log.w(TAG, "Boundary needs review: score=$bestScore candidates=${allCandidates.size} hasSuggestion=${suggestion != null}")
                DetectionResult(
                    success = false,
                    corners = suggestion ?: getDefaultCorners(originalW, originalH),
                    confidence = if (suggestion != null) bestScore else 0f,
                    message = if (suggestion != null) "Check the suggested boundary before cropping." else "No reliable boundary found. Adjust the four corners."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during document detection", e)
            DetectionResult(
                success = false,
                corners = getFullImageCorners(bitmap.width, bitmap.height),
                confidence = 0f,
                message = "Detection failed. Adjust corners.",
                status = DetectionStatus.FAILED
            )
        } finally {
            resources.distinctBy { it.nativeObj }.forEach { it.release() }
            allocationScope.remove()
            if (processingBitmap !== bitmap) processingBitmap?.recycle()
        }
    }

    private data class LineSegment(
        val x1: Double, val y1: Double,
        val x2: Double, val y2: Double,
        val len: Double,
        val midX: Double, val midY: Double
    )

    /**
     * Orders 4 points consecutively around their centroid starting from Top-Left.
     */
    private fun orderQuadPoints(pts: Array<Point>): Array<Point> {
        if (pts.size != 4) return pts
        val cx = (pts[0].x + pts[1].x + pts[2].x + pts[3].x) / 4.0
        val cy = (pts[0].y + pts[1].y + pts[2].y + pts[3].y) / 4.0
        val sortedByAngle = pts.sortedBy { p ->
            atan2(p.y - cy, p.x - cx)
        }
        var tlIndex = 0
        var minSum = Double.MAX_VALUE
        for (i in 0 until 4) {
            val sum = sortedByAngle[i].x + sortedByAngle[i].y
            if (sum < minSum) {
                minSum = sum
                tlIndex = i
            }
        }
        return Array(4) { i ->
            sortedByAngle[(tlIndex + i) % 4]
        }
    }

    /**
     * Evaluates a candidate quadrilateral using boundary evidence and weak geometric preferences:
     * 1. S_edge (40%): Alignment of all 4 perimeter segments with the dilated Canny edge map.
     * 2. S_content (25%): Density of text/graphics/barcode edge pixels inside the document.
     * 3. S_geom (35%): Rectangularity, near-90 angles, and ID-1 aspect ratio bonus.
     */
    private fun scoreCandidate(
        pts: Array<Point>,
        dilatedEdges: Mat,
        cannyEdges: Mat,
        totalArea: Double,
        imgW: Double,
        imgH: Double,
        mode: DetectionMode
    ): Float {
        val ordered = orderQuadPoints(pts)
        if (!areAnglesReasonable(ordered) || isImageBoundary(ordered, imgW, imgH)) {
            return 0f
        }

        val mop = hold(MatOfPoint(*ordered))
        val quadArea = Imgproc.contourArea(mop)
        mop.release()

        val minArea = totalArea * 0.05
        if (quadArea < minArea || quadArea > totalArea * 0.96) {
            return 0f
        }

        val areaRatio = (quadArea / totalArea).toFloat()
        val sideLengths = ordered.indices.map { i ->
            hypot(ordered[i].x - ordered[(i + 1) % 4].x, ordered[i].y - ordered[(i + 1) % 4].y)
        }
        val width = (sideLengths[0] + sideLengths[2]) / 2.0
        val height = (sideLengths[1] + sideLengths[3]) / 2.0
        val aspect = max(width, height) / min(width, height).coerceAtLeast(1.0)
        // Prevent elongated false areas (ruler, keyboard, desk shadow, banners)
        if (aspect > 2.8 || (mode == DetectionMode.ID_CARD && aspect > 2.4)) return 0f

        // 1. Geometric Score (0.0 to 1.0)
        val sGeom = calculateQuadScore(ordered, areaRatio, mode)

        // 2. Edge Alignment Score (0.0 to 1.0)
        // Samples 25 points along each of the 4 perimeter edges against dilated Canny edge map
        val numSamplesPerEdge = 25
        var totalEdgeHits = 0
        var minEdgeHits = numSamplesPerEdge
        val rows = dilatedEdges.rows()
        val cols = dilatedEdges.cols()

        for (i in 0 until 4) {
            val p1 = ordered[i]
            val p2 = ordered[(i + 1) % 4]
            var edgeHits = 0
            for (s in 1..numSamplesPerEdge) {
                val t = s.toDouble() / (numSamplesPerEdge + 1).toDouble()
                val sx = (p1.x + t * (p2.x - p1.x)).roundToInt().coerceIn(0, cols - 1)
                val sy = (p1.y + t * (p2.y - p1.y)).roundToInt().coerceIn(0, rows - 1)
                val pixelVal = dilatedEdges.get(sy, sx)
                if (pixelVal != null && pixelVal[0] > 100.0) {
                    edgeHits++
                }
            }
            totalEdgeHits += edgeHits
            if (edgeHits < minEdgeHits) {
                minEdgeHits = edgeHits
            }
        }
        val avgEdgeHitRatio = totalEdgeHits.toFloat() / (4 * numSamplesPerEdge)
        val minEdgeHitRatio = minEdgeHits.toFloat() / numSamplesPerEdge
        // Reject candidate only if edge alignment is virtually absent
        if (avgEdgeHitRatio < 0.20f || totalEdgeHits < 12) return 0f
        val sEdge = (avgEdgeHitRatio * 0.75f + minEdgeHitRatio * 0.25f).coerceIn(0f, 1f)

        // 3. Content / Text Density Score (0.0 to 1.0)
        // Documents (ID cards, papers) have text, photos, logos inside.
        // Plain floor, bedsheets, or tables have virtually zero edge pixels inside.
        val polyMask = Mat.zeros(cannyEdges.size(), CvType.CV_8UC1)
        val ptsMop = hold(MatOfPoint(*ordered))
        Imgproc.fillConvexPoly(polyMask, ptsMop, Scalar(255.0))
        ptsMop.release()

        val erodeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        val erodedMask = hold(Mat())
        Imgproc.erode(polyMask, erodedMask, erodeKernel)
        polyMask.release()
        erodeKernel.release()

        val insideEdges = hold(Mat())
        Core.bitwise_and(cannyEdges, erodedMask, insideEdges)
        val nonZeroCount = Core.countNonZero(insideEdges)
        erodedMask.release()
        insideEdges.release()

        val internalDensity = if (quadArea > 0) nonZeroCount.toDouble() / quadArea else 0.0
        val sContent = when {
            internalDensity < 0.002 -> 0.30f  // Plain white card is still valid
            internalDensity < 0.008 -> (0.30f + ((internalDensity - 0.002) / 0.006 * 0.50f).toFloat())
            internalDensity in 0.008..0.25 -> 1.0f
            internalDensity in 0.25..0.40 -> (1.0f - ((internalDensity - 0.25) / 0.15 * 0.40f).toFloat())
            else -> 0.45f
        }

        // Weighted composite score: require strong edge alignment + content + geometry
        val sizeSupport = (areaRatio / 0.20f).coerceIn(0f, 1f)
        val totalScore = (0.45f * sEdge + 0.20f * sContent + 0.25f * sGeom +
            0.10f * sizeSupport).coerceIn(0f, 1f)
        return totalScore
    }

    /**
     * White / Light ID Card Segmentation:
     * Aadhaar, PAN, DL, and identity cards are predominantly white or very light.
     * In HSV, white cards have low saturation and high value.
     * Morphological closing (15x15) fills in all text, photos, and minor finger occlusions.
     */
    private fun runWhiteCardSegmentationPass(
        colorMat: Mat,
        skinMask: Mat,
        totalArea: Double,
        imgW: Double,
        imgH: Double
    ): List<Array<Point>> {
        val hsvMat = hold(Mat())
        Imgproc.cvtColor(colorMat, hsvMat, Imgproc.COLOR_RGBA2RGB)
        val hsv = hold(Mat())
        Imgproc.cvtColor(hsvMat, hsv, Imgproc.COLOR_RGB2HSV)
        hsvMat.release()

        val channels = mutableListOf<Mat>()
        Core.split(hsv, channels) // H=0, S=1, V=2
        val sMat = channels[1]
        val vMat = channels[2]

        // White card condition: low saturation (S < 95) AND adequate brightness (V > 90)
        val sMask = hold(Mat())
        val vMask = hold(Mat())
        Imgproc.threshold(sMat, sMask, 95.0, 255.0, Imgproc.THRESH_BINARY_INV) // S < 95
        Imgproc.threshold(vMat, vMask, 90.0, 255.0, Imgproc.THRESH_BINARY)     // V > 90

        val cardMask = hold(Mat())
        Core.bitwise_and(sMask, vMask, cardMask)
        // Subtract skin mask to disconnect any fingers holding the card
        Core.subtract(cardMask, skinMask, cardMask)

        // Morphological Close to fuse the entire card into a single solid polygon
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(25.0, 25.0))
        val closed = hold(Mat())
        Imgproc.morphologyEx(cardMask, closed, Imgproc.MORPH_CLOSE, kernel)

        val candidates = extractCandidateQuads(closed, totalArea, imgW, imgH)

        hsv.release()
        channels.forEach { it.release() }
        sMask.release()
        vMask.release()
        cardMask.release()
        kernel.release()
        closed.release()

        return candidates
    }

    /**
     * Hough Line Segment Intersection:
     * Line recovery: ID cards and documents have straight borders.
     * When a thumb or finger covers a corner, the straight horizontal and vertical edges
     * still exist. Computing their mathematical intersection produces the true corners!
     */
    private fun runHoughLinePass(
        blurred: Mat,
        skinMask: Mat,
        totalArea: Double,
        imgW: Double,
        imgH: Double
    ): List<Array<Point>> {
        val edges = hold(Mat())
        Imgproc.Canny(blurred, edges, 35.0, 105.0)
        Core.subtract(edges, skinMask, edges)

        val linesMat = hold(Mat())
        Imgproc.HoughLinesP(edges, linesMat, 1.0, Math.PI / 180, 35, 35.0, 15.0)
        val numLines = linesMat.rows()
        if (numLines < 4) {
            edges.release()
            linesMat.release()
            return emptyList()
        }

        val segments = (0 until numLines).mapNotNull { i ->
            val v = linesMat.get(i, 0) ?: return@mapNotNull null
            LineSegment(v[0],v[1],v[2],v[3],hypot(v[2]-v[0],v[3]-v[1]),(v[0]+v[2])/2,(v[1]+v[3])/2)
        }.sortedByDescending { it.len }.take(18)
        edges.release(); linesMat.release()
        fun angle(line: LineSegment) = (atan2(line.y2-line.y1,line.x2-line.x1)+Math.PI)%Math.PI
        fun difference(a: LineSegment,b: LineSegment): Double {
            val d = abs(angle(a)-angle(b)); return min(d,Math.PI-d)
        }
        val candidates = mutableListOf<Array<Point>>()
        for (i in segments.indices) for (j in i+1 until segments.size) {
            val a = segments[i]; val b = segments[j]
            if (difference(a,b)>.45) continue
            val cross = segments.filter { difference(a,it)>.7 }.take(8)
            for (k in cross.indices) for (l in k+1 until cross.size) {
                val c = cross[k]; val d = cross[l]
                if (difference(c,d)>.45) continue
                val points = listOfNotNull(intersectLines(a,c),intersectLines(a,d),intersectLines(b,d),intersectLines(b,c))
                if (points.size!=4 || points.any { it.x !in 0.0..(imgW-1) || it.y !in 0.0..(imgH-1) }) continue
                val ordered = orderQuadPoints(points.toTypedArray())
                if (!areAnglesReasonable(ordered)) continue
                val quad = hold(MatOfPoint(*ordered))
                try {
                    if (Imgproc.isContourConvex(quad) && Imgproc.contourArea(quad) in totalArea*.04..totalArea*1.01) candidates.add(ordered)
                } finally { quad.release() }
            }
        }
        return candidates
    }

    private fun intersectLines(l1: LineSegment, l2: LineSegment): Point? {
        val d = (l1.x1 - l1.x2) * (l2.y1 - l2.y2) - (l1.y1 - l1.y2) * (l2.x1 - l2.x2)
        if (abs(d) < 1e-4) return null
        val term1 = l1.x1 * l1.y2 - l1.y1 * l1.x2
        val term2 = l2.x1 * l2.y2 - l2.y1 * l2.x2
        val px = (term1 * (l2.x1 - l2.x2) - (l1.x1 - l1.x2) * term2) / d
        val py = (term1 * (l2.y1 - l2.y2) - (l1.y1 - l1.y2) * term2) / d
        return Point(px, py)
    }

    private fun runCannyPass(
        blurred: Mat,
        skinMask: Mat,
        totalArea: Double,
        imgW: Double,
        imgH: Double,
        useOtsu: Boolean,
        fixedLow: Double = 30.0,
        fixedHigh: Double = 100.0
    ): List<Array<Point>> {
        val edges = hold(Mat())
        if (useOtsu) {
            val dummy = hold(Mat())
            val otsuThresh = Imgproc.threshold(blurred, dummy, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            dummy.release()

            val high = otsuThresh.coerceIn(40.0, 220.0)
            val low = high * 0.4
            Imgproc.Canny(blurred, edges, low, high)
        } else {
            Imgproc.Canny(blurred, edges, fixedLow, fixedHigh)
        }

        // Subtract skin mask
        Core.subtract(edges, skinMask, edges)

        // Multi-scale dilate + close to bridge edge gaps
        val candidates = mutableListOf<Array<Point>>()
        for (ksize in doubleArrayOf(5.0, 7.0, 9.0)) {
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(ksize, ksize))
            val dil = hold(Mat())
            Imgproc.dilate(edges, dil, kernel)
            val closed = hold(Mat())
            Imgproc.morphologyEx(dil, closed, Imgproc.MORPH_CLOSE, kernel)
            candidates.addAll(extractCandidateQuads(closed, totalArea, imgW, imgH))
            kernel.release()
            dil.release()
            closed.release()
        }
        edges.release()
        return candidates
    }

    private fun runMorphGradientPass(
        grayMat: Mat,
        skinMask: Mat,
        totalArea: Double,
        imgW: Double,
        imgH: Double
    ): List<Array<Point>> {
        val kernel3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val gradient = hold(Mat())
        Imgproc.morphologyEx(grayMat, gradient, Imgproc.MORPH_GRADIENT, kernel3)

        val thresh = hold(Mat())
        Imgproc.threshold(gradient, thresh, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)

        val kernel5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val closed = hold(Mat())
        Imgproc.morphologyEx(thresh, closed, Imgproc.MORPH_CLOSE, kernel5)

        // Subtract skin mask
        Core.subtract(closed, skinMask, closed)

        val candidates = extractCandidateQuads(closed, totalArea, imgW, imgH)

        kernel3.release()
        gradient.release()
        thresh.release()
        kernel5.release()
        closed.release()

        return candidates
    }

    private fun runMultiChannelPass(
        colorMat: Mat,
        skinMask: Mat,
        totalArea: Double,
        imgW: Double,
        imgH: Double
    ): List<Array<Point>> {
        val channels = mutableListOf<Mat>()
        Core.split(colorMat, channels)

        val combinedEdges = Mat.zeros(colorMat.size(), CvType.CV_8UC1)
        val channelEdges = hold(Mat())

        for (i in 0 until min(3, channels.size)) {
            val ch = channels[i]
            val blurredCh = hold(Mat())
            Imgproc.GaussianBlur(ch, blurredCh, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurredCh, channelEdges, 25.0, 85.0)
            Core.bitwise_or(combinedEdges, channelEdges, combinedEdges)
            blurredCh.release()
        }

        channelEdges.release()
        channels.forEach { it.release() }

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val closed = hold(Mat())
        Imgproc.morphologyEx(combinedEdges, closed, Imgproc.MORPH_CLOSE, kernel)

        // Subtract skin mask
        Core.subtract(closed, skinMask, closed)

        val candidates = extractCandidateQuads(closed, totalArea, imgW, imgH)

        combinedEdges.release()
        kernel.release()
        closed.release()

        return candidates
    }

    private fun runAdaptiveThresholdPass(
        blurred: Mat,
        skinMask: Mat,
        totalArea: Double,
        imgW: Double,
        imgH: Double
    ): List<Array<Point>> {
        val thresh = hold(Mat())
        Imgproc.adaptiveThreshold(
            blurred,
            thresh,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            21,
            4.0
        )

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        val closed = hold(Mat())
        Imgproc.morphologyEx(thresh, closed, Imgproc.MORPH_CLOSE, kernel)

        // Subtract skin mask
        Core.subtract(closed, skinMask, closed)

        val candidates = extractCandidateQuads(closed, totalArea, imgW, imgH)

        thresh.release()
        kernel.release()
        closed.release()

        return candidates
    }

    /**
     * Extracts candidate quadrilaterals from a binary edge/threshold image.
     * Evaluates convex hulls, wide-range Douglas-Peucker approximations, rounded corner resolution,
     * and minAreaRect rotated bounding boxes.
     */
    private fun extractCandidateQuads(binaryMat: Mat, totalArea: Double, imgW: Double, imgH: Double): List<Array<Point>> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = hold(Mat())
        Imgproc.findContours(
            binaryMat,
            contours,
            hierarchy,
            Imgproc.RETR_LIST,
            Imgproc.CHAIN_APPROX_SIMPLE
        )
        hierarchy.release()

        val minArea = totalArea * 0.05
        val maxArea = totalArea * 0.96

        val filtered = contours.filter {
            val a = Imgproc.contourArea(it)
            a in minArea..maxArea
        }.sortedByDescending { Imgproc.contourArea(it) }

        val candidates = mutableListOf<Array<Point>>()
        val epsilons = doubleArrayOf(
            0.01, 0.015, 0.02, 0.025, 0.03, 0.04, 0.05, 0.06, 0.07, 0.08, 0.10
        )

        for (contour in filtered.take(25)) {
            val hullIndices = hold(MatOfInt())
            Imgproc.convexHull(contour, hullIndices)
            val contourPts = contour.toArray()
            val hullPts = hullIndices.toArray().map { contourPts[it] }.toTypedArray()
            hullIndices.release()

            if (hullPts.size < 4) continue

            val hullMop = hold(MatOfPoint(*hullPts))
            val hull2f = hold(MatOfPoint2f(*hullPts))
            val perimeter = Imgproc.arcLength(hull2f, true)

            // A. Direct 4 extreme quadrant corners from convex hull (robust against finger occlusions)
            val extremeQuad = extract4ExtremeCorners(hullPts)
            if (extremeQuad != null) {
                val ordered = orderQuadPoints(extremeQuad)
                if (areAnglesReasonable(ordered) && !isImageBoundary(ordered, imgW, imgH)) {
                    candidates.add(ordered)
                }
            }

            // B. Wide-range Douglas-Peucker approximation
            for (eps in epsilons) {
                val approx2f = hold(MatOfPoint2f())
                Imgproc.approxPolyDP(hull2f, approx2f, perimeter * eps, true)

                if (approx2f.total() == 4L) {
                    val points = approx2f.toArray()
                    val mop = hold(MatOfPoint(*points))
                    if (Imgproc.isContourConvex(mop)) {
                        val ordered = orderQuadPoints(points)
                        if (areAnglesReasonable(ordered) && !isImageBoundary(ordered, imgW, imgH)) {
                            candidates.add(ordered)
                        }
                    }
                    mop.release()
                }
                approx2f.release()
            }

            // C. 4 extreme quadrant points on polygon approximation
            for (approxEps in listOf(0.025, 0.04)) {
                val approx2f = hold(MatOfPoint2f())
                Imgproc.approxPolyDP(hull2f, approx2f, perimeter * approxEps, true)
                val totalPts = approx2f.total()
                if (totalPts in 4L..20L) {
                    val pts = approx2f.toArray()
                    val quadPts = extract4ExtremeCorners(pts)
                    if (quadPts != null) {
                        val ordered = orderQuadPoints(quadPts)
                        if (areAnglesReasonable(ordered) && !isImageBoundary(ordered, imgW, imgH)) {
                            candidates.add(ordered)
                        }
                    }
                }
                approx2f.release()
            }

            // D. Rotated Bounding Box (minAreaRect)
            val minRect = Imgproc.minAreaRect(hull2f)
            val rectArea = minRect.size.area()
            val cArea = Imgproc.contourArea(contour)
            if (rectArea > 0) {
                val fillRatio = cArea / rectArea
                if (fillRatio in 0.45..1.15) {
                    val boxPts = Array(4) { Point() }
                    minRect.points(boxPts)
                    val ordered = orderQuadPoints(boxPts)
                    if (areAnglesReasonable(ordered) && !isImageBoundary(ordered, imgW, imgH)) {
                        candidates.add(ordered)
                    }
                }
            }

            hullMop.release()
            hull2f.release()
        }

        contours.forEach { it.release() }
        return candidates
    }

    /**
     * Checks if contour is the camera frame / photo sensor boundary.
     * Rejects candidates that span almost the entire photo or have 3+ corners on the outer image frame.
     */
    private fun isImageBoundary(pts: Array<Point>, imgW: Double, imgH: Double): Boolean {
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        val actualW = (imgW - 2 * BORDER_PAD).coerceAtLeast(1.0)
        val actualH = (imgH - 2 * BORDER_PAD).coerceAtLeast(1.0)
        val spanW = (maxX - minX) / actualW
        val spanH = (maxY - minY) / actualH
        if (spanW > 0.985 && spanH > 0.985) return true

        val exactBorderCount = pts.count { p ->
            (p.x <= BORDER_PAD + 2 && (p.y <= BORDER_PAD + 2 || p.y >= imgH - BORDER_PAD - 2)) ||
            (p.x >= imgW - BORDER_PAD - 2 && (p.y <= BORDER_PAD + 2 || p.y >= imgH - BORDER_PAD - 2))
        }
        return exactBorderCount >= 4 && (spanW > 0.98 && spanH > 0.98)
    }

    /**
     * Extracts 4 corner points from a 5..14 point contour with rounded corners
     * using the 4 quadrant extreme projections (TL, TR, BR, BL).
     */
    private fun extract4ExtremeCorners(pts: Array<Point>): Array<Point>? {
        if (pts.size < 4) return null

        var tl = pts[0] // min (x + y)
        var br = pts[0] // max (x + y)
        var tr = pts[0] // max (x - y)
        var bl = pts[0] // min (x - y)

        var minSum = tl.x + tl.y
        var maxSum = br.x + br.y
        var maxDiff = tr.x - tr.y
        var minDiff = bl.x - bl.y

        for (p in pts) {
            val sum = p.x + p.y
            val diff = p.x - p.y

            if (sum < minSum) {
                minSum = sum
                tl = p
            }
            if (sum > maxSum) {
                maxSum = sum
                br = p
            }
            if (diff > maxDiff) {
                maxDiff = diff
                tr = p
            }
            if (diff < minDiff) {
                minDiff = diff
                bl = p
            }
        }

        val result = arrayOf(tl, tr, br, bl)
        val distinctCount = result.map { "${it.x.toInt()},${it.y.toInt()}" }.toSet().size
        return if (distinctCount == 4) result else null
    }

    /**
     * Verifies that all 4 angles are between 38.7 and 141.3 degrees (cosine magnitude < 0.78),
     * and opposite sides are reasonably parallel to reject diagonal cross-cuts.
     */
    private fun areAnglesReasonable(pts: Array<Point>): Boolean {
        if (pts.size != 4) return false
        for (i in 0 until 4) {
            val p0 = pts[i]
            val p1 = pts[(i + 1) % 4]
            val p2 = pts[(i + 2) % 4]

            val v1x = p0.x - p1.x
            val v1y = p0.y - p1.y
            val v2x = p2.x - p1.x
            val v2y = p2.y - p1.y

            val dot = v1x * v2x + v1y * v2y
            val mag1 = hypot(v1x, v1y)
            val mag2 = hypot(v2x, v2y)

            if (mag1 < 1e-4 || mag2 < 1e-4) return false
            val cos = abs(dot / (mag1 * mag2))
            if (cos > 0.78) return false // Angle between 38.7 and 141.3 degrees
        }

        // Opposite sides must be reasonably parallel (within ~20 degrees)
        fun edgeAngle(pA: Point, pB: Point): Double = (atan2(pB.y - pA.y, pB.x - pA.x) + 2 * Math.PI) % Math.PI
        val topA = edgeAngle(pts[0], pts[1])
        val btmA = edgeAngle(pts[3], pts[2])
        val diffH = abs(topA - btmA).let { min(it, Math.PI - it) }

        val lftA = edgeAngle(pts[0], pts[3])
        val rgtA = edgeAngle(pts[1], pts[2])
        val diffV = abs(lftA - rgtA).let { min(it, Math.PI - it) }

        if (diffH > 0.35 || diffV > 0.35) return false // ~20 degrees max skew between opposite sides

        return true
    }

    private fun calculateQuadScore(pts: Array<Point>, areaRatio: Float, mode: DetectionMode): Float {
        // 1. Area score: ideal documents in photos cover between 25% and 96% of the image
        val areaScore = when {
            areaRatio in 0.25f..0.96f -> 1.0f
            areaRatio > 0.96f -> 0.85f
            else -> (areaRatio / 0.25f).coerceIn(0.2f, 1.0f)
        }

        // 2. Rectangularity score: opposite sides should be nearly equal in length
        val w1 = hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y)
        val w2 = hypot(pts[2].x - pts[3].x, pts[2].y - pts[3].y)
        val h1 = hypot(pts[3].x - pts[0].x, pts[3].y - pts[0].y)
        val h2 = hypot(pts[2].x - pts[1].x, pts[2].y - pts[1].y)

        val maxW = max(w1, w2).coerceAtLeast(1.0)
        val maxH = max(h1, h2).coerceAtLeast(1.0)
        val wRatio = min(w1, w2) / maxW
        val hRatio = min(h1, h2) / maxH
        val rectScore = ((wRatio + hRatio) / 2.0).toFloat().coerceIn(0f, 1f)

        // 3. Aspect ratio score (ID card 1.5858, A4 1.414, standard doc 1.33..1.65)
        val avgW = (w1 + w2) / 2.0
        val avgH = (h1 + h2) / 2.0
        val aspect = if (avgH > 0) (avgW / avgH).toFloat() else 1f
        val normAspect = if (aspect >= 1f) aspect else (1f / aspect)

        val idDiff = abs(normAspect - ID_CARD_ASPECT_RATIO)
        val a4Diff = abs(normAspect - 1.414f)
        val aspectBonus = if (mode == DetectionMode.DOCUMENT) {
            if (a4Diff < 0.25f || normAspect in 1.20f..1.65f) 0.30f else 0.20f
        } else if (idDiff < 0.20f) {
            0.30f // High priority for standard ID Card ratio
        } else if (idDiff < 0.35f || a4Diff < 0.25f) {
            0.20f
        } else {
            0.10f
        }

        return (areaScore * 0.35f + rectScore * 0.35f + aspectBonus).coerceIn(0f, 1f)
    }

    /**
     * Fits standard ISO/IEC 7810 ID-1 aspect ratio (85.6mm x 53.98mm = 1.5858)
     * centered directly on the existing detected document corners.
     */
    fun fitIdCardRatioToCorners(currentCorners: List<PointF>, imgW: Int, imgH: Int): List<PointF> =
        if (CropGeometry.valid(currentCorners, imgW, imgH)) currentCorners else getFullImageCorners(imgW, imgH)

    /**
     * Standard ISO/IEC 7810 ID-1 card (85.6mm x 53.98mm, ratio 1.5858).
     * For phone photos, the card is almost always photographed horizontally.
     * Centers a horizontal ID-1 card frame within the photo.
     */
    fun getIdCardCorners(width: Int, height: Int): List<PointF> {
        val targetRatio = ID_CARD_ASPECT_RATIO // ID cards are horizontal (85.6 x 54)
        var cardW = width * 0.86f
        var cardH = cardW / targetRatio

        if (cardH > height * 0.86f) {
            cardH = height * 0.86f
            cardW = cardH * targetRatio
        }

        val ox = (width - cardW) / 2f
        val oy = (height - cardH) / 2f

        return listOf(
            PointF(ox, oy),
            PointF(ox + cardW, oy),
            PointF(ox + cardW, oy + cardH),
            PointF(ox, oy + cardH)
        )
    }

    fun getFullImageCorners(width: Int, height: Int): List<PointF> {
        return listOf(
            PointF(0f, 0f),
            PointF((width - 1).toFloat(), 0f),
            PointF((width - 1).toFloat(), (height - 1).toFloat()),
            PointF(0f, (height - 1).toFloat())
        )
    }

    fun getDefaultCorners(width: Int, height: Int): List<PointF> {
        val mx = width * 0.06f
        val my = height * 0.06f
        return listOf(
            PointF(mx, my),
            PointF(width - mx, my),
            PointF(width - mx, height - my),
            PointF(mx, height - my)
        )
    }

    fun detectOrDefault(bitmap: Bitmap): DetectionResult = detect(bitmap)

    /**
     * Orders 4 corners clockwise starting from Top-Left:
     * 0: Top-Left (smallest x+y)
     * 1: Top-Right (largest x-y)
     * 2: Bottom-Right (largest x+y)
     * 3: Bottom-Left (smallest x-y)
     */
    fun orderCorners(corners: List<PointF>): List<PointF> {
        if (corners.size != 4) return corners

        // Compute centroid
        val cx = corners.sumOf { it.x.toDouble() } / 4.0
        val cy = corners.sumOf { it.y.toDouble() } / 4.0

        // Sort by angle around centroid (-PI to PI)
        val sortedByAngle = corners.sortedBy { p ->
            atan2(p.y - cy, p.x - cx)
        }

        // Find the one closest to top-left (min x+y)
        var tlIndex = 0
        var minSum = Float.MAX_VALUE
        for (i in 0 until 4) {
            val sum = sortedByAngle[i].x + sortedByAngle[i].y
            if (sum < minSum) {
                minSum = sum
                tlIndex = i
            }
        }

        // Rotate list so Top-Left is first, proceeding clockwise
        return List(4) { i ->
            sortedByAngle[(tlIndex + i) % 4]
        }
    }
}
