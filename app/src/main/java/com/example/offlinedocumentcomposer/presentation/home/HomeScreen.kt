package com.example.offlinedocumentcomposer.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartIdCardFlow: () -> Unit,
    onStartSingleDocFlow: () -> Unit,
    onOpenComposer: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenPdfResizer: () -> Unit,
    onOpenPassportPhoto: () -> Unit,
    onOpenImageResizer: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "DocStudio",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Offline Document & Photo Suite",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                // Privacy Badge Banner
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFE8F5E9),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "100% Offline • No data leaves your phone • Private",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF1B5E20),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Featured Hero Card: 2-Sided ID Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onStartIdCardFlow() },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF2563EB),
                                        Color(0xFF1E40AF),
                                        Color(0xFF0F172A)
                                    )
                                )
                            )
                            .padding(22.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFFFD54F)
                                ) {
                                    Text(
                                        text = "RECOMMENDED",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFF4527A0)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.Badge,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "2-Sided ID Card to A4",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "Photo front & back (Aadhaar, PAN, License), auto-crop edges, enhance to crisp B&W, and place side-by-side on an A4 sheet ready for PDF export.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.85f),
                                lineHeight = 20.sp
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            Button(
                                onClick = onStartIdCardFlow,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color(0xFF1E3A8A)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Start ID Card Scanner", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Quick Actions Section Header
            item {
                Text(
                    text = "Quick Tools",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            item {
                HomeActionCard(
                    icon = Icons.Default.DocumentScanner,
                    iconTint = Color(0xFF059669),
                    iconBg = Color(0xFFD1FAE5),
                    title = "Image to PDF",
                    subtitle = "Scan or import images, crop, enhance, and combine pages",
                    onClick = onOpenScanner
                )
            }
            item {
                HomeActionCard(
                    icon = Icons.Default.Compress,
                    iconTint = Color(0xFF2563EB),
                    iconBg = Color(0xFFDBEAFE),
                    title = "PDF Resizer",
                    subtitle = "Reduce PDF size while keeping text, links, and forms",
                    onClick = onOpenPdfResizer
                )
            }
            item {
                HomeActionCard(
                    icon = Icons.Default.AccountBox,
                    iconTint = Color(0xFFD97706),
                    iconBg = Color(0xFFFEF3C7),
                    title = "Passport Photo Maker",
                    subtitle = "Print sheets (4×6, A4), Indian 35×45mm photos & online forms",
                    onClick = onOpenPassportPhoto
                )
            }
            item {
                HomeActionCard(
                    icon = Icons.Default.AspectRatio,
                    iconTint = Color(0xFFE11D48),
                    iconBg = Color(0xFFFFE4E6),
                    title = "Image Resizer",
                    subtitle = "Resize by px, %, mm/cm, target KB limit with aspect ratio lock",
                    onClick = onOpenImageResizer
                )
            }

            // Action Cards
            item {
                HomeActionCard(
                    icon = Icons.Default.Crop,
                    iconTint = Color(0xFF0284C7),
                    iconBg = Color(0xFFE0F2FE),
                    title = "Single Document Scan",
                    subtitle = "Capture 1 document, detect corners, enhance, and save",
                    onClick = onStartSingleDocFlow
                )
            }

            item {
                HomeActionCard(
                    icon = Icons.Default.Layers,
                    iconTint = Color(0xFF7C3AED),
                    iconBg = Color(0xFFF3E8FF),
                    title = "Page Composer Canvas",
                    subtitle = "Open free A4 canvas to move, resize, and arrange images",
                    onClick = onOpenComposer
                )
            }

            item {
                HomeActionCard(
                    icon = Icons.Default.Folder,
                    iconTint = Color(0xFFD97706),
                    iconBg = Color(0xFFFEF3C7),
                    title = "Recent Projects & Exports",
                    subtitle = "View and re-share exported PDFs and documents",
                    onClick = onOpenRecent
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun HomeActionCard(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = iconBg,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}