package com.narchives.reader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.narchives.reader.data.local.entity.ArchiveEventEntity
import com.narchives.reader.data.local.entity.ProfileEntity
import com.narchives.reader.ui.util.extractDomain
import com.narchives.reader.ui.util.formatFileSize
import com.narchives.reader.ui.util.formatRelativeTime
import com.narchives.reader.ui.util.truncateUrl

@Composable
fun ArchiveCard(
    archive: ArchiveEventEntity,
    profile: ProfileEntity?,
    onClick: () -> Unit,
    onAuthorClick: (() -> Unit)? = null,
    onSaveClick: (() -> Unit)? = null,
    isSaved: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Domain + timestamp row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = extractDomain(archive.archivedUrl),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = formatRelativeTime(archive.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Title
            Text(
                text = archive.title ?: extractDomain(archive.archivedUrl),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(4.dp))

            // URL
            Text(
                text = truncateUrl(archive.archivedUrl),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Author row + badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Author avatar
                if (profile?.pictureUrl != null) {
                    AsyncImage(
                        model = profile.pictureUrl,
                        contentDescription = "Author avatar",
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .then(
                                if (onAuthorClick != null) Modifier.clickable(onClick = onAuthorClick)
                                else Modifier
                            ),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Author name
                Text(
                    text = profile?.displayName
                        ?: profile?.name
                        ?: archive.authorPubkey.take(8) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (onAuthorClick != null) {
                        Modifier.clickable(onClick = onAuthorClick)
                    } else Modifier,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.weight(1f))

                // Badges
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (archive.hasOts) {
                        Text(
                            text = "OTS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    archive.archiveMode?.let { mode ->
                        Text(
                            text = mode.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    val sizeStr = formatFileSize(archive.waczSize)
                    if (sizeStr.isNotEmpty()) {
                        Text(
                            text = sizeStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (onSaveClick != null) {
                        IconButton(
                            onClick = onSaveClick,
                            modifier = Modifier.size(28.dp).offset(x = 4.dp),
                        ) {
                            Icon(
                                imageVector = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (isSaved) "Unsave" else "Save offline",
                                modifier = Modifier.size(18.dp),
                                tint = if (isSaved) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
