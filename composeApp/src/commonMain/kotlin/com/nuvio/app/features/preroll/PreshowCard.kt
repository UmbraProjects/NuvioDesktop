package com.nuvio.app.features.preroll

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** What a drawn card says. Used when a rating or feature-presentation slot found no clip to play. */
sealed interface PreshowCardModel {
    data class Rating(
        val certificate: String,
        val region: RatingRegion,
        val title: String,
        /** The BBFC's content advisory ("Contains moderate threat, ..."), when it could be found. */
        val advisory: String? = null,
    ) : PreshowCardModel
    data class FeaturePresentation(val title: String) : PreshowCardModel
}

private const val CARD_MS = 5_000L

private fun certificateColor(certificate: String): Color = when (certificate) {
    "U", "G" -> Color(0xFF2E9E4F)
    "PG" -> Color(0xFFF2C230)
    "12A", "PG-13" -> Color(0xFFF08A24)
    "15", "R" -> Color(0xFFE0457B)
    "18", "NC-17" -> Color(0xFFD32F2F)
    else -> Color(0xFF888888)
}

/** The classification's own wording for who a BBFC certificate is for. */
private fun bbfcGuidance(certificate: String): String = when (certificate.uppercase()) {
    "U" -> "Suitable for all"
    "PG" -> "General viewing, but some scenes may be unsuitable for young children"
    "12", "12A" -> "Suitable only for persons of 12 years and over"
    "15" -> "Suitable only for persons of 15 years and over"
    "18" -> "Suitable only for persons of 18 years and over"
    else -> ""
}

/**
 * The BBFC card: a round certificate badge on the left; on the right the title in capitals, a rule
 * in the certificate's colour, the content advisory (when the BBFC's page could be read) and who
 * the film is suitable for.
 *
 * Deliberately text and shapes only. The BBFC's own logo, tagline and web address are not drawn.
 */
@Composable
private fun BbfcRatingCard(model: PreshowCardModel.Rating) {
    val accent = certificateColor(model.certificate)
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            "BRITISH BOARD OF FILM CLASSIFICATION",
            color = Color(0xFFBBBBBB),
            fontSize = 16.sp,
            letterSpacing = 3.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 96.dp, top = 72.dp),
        )
        Row(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 96.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            Box(modifier = Modifier.size(220.dp).background(Color.White, CircleShape).padding(10.dp)) {
                Box(
                    modifier = Modifier.fillMaxSize().background(accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        model.certificate,
                        color = if (model.certificate == "PG") Color.Black else Color.White,
                        fontSize = if (model.certificate.length > 2) 64.sp else 96.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    model.title.uppercase(),
                    color = Color.White,
                    fontSize = 44.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).background(accent))
                Spacer(Modifier.height(24.dp))
                model.advisory?.let {
                    Text(it, color = accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                }
                Text(bbfcGuidance(model.certificate), color = Color.White, fontSize = 26.sp)
            }
        }
    }
}

@Composable
fun PreshowCard(model: PreshowCardModel, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(model) {
        delay(CARD_MS)
        onFinished()
    }
    Box(modifier = modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (model is PreshowCardModel.Rating && model.region == RatingRegion.Bbfc) {
            BbfcRatingCard(model)
            return@Box
        }
        Column(
            modifier = Modifier.padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (model) {
                is PreshowCardModel.Rating -> {
                    val body = if (model.region == RatingRegion.Bbfc) "BRITISH BOARD OF FILM CLASSIFICATION" else "MOTION PICTURE ASSOCIATION"
                    val verb = if (model.region == RatingRegion.Bbfc) "has been classified for cinema exhibition" else "is rated"
                    Text(body, color = Color.White, fontSize = 20.sp, letterSpacing = 3.sp)
                    Text("This is to certify that", color = Color(0xFFBBBBBB), fontSize = 18.sp)
                    Text(
                        model.title.uppercase(),
                        color = Color.White,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(verb, color = Color(0xFFBBBBBB), fontSize = 18.sp)
                    if (hasBbfcSymbol(model.certificate) && model.region == RatingRegion.Bbfc) {
                        BbfcSymbol(model.certificate, size = 220.dp)
                    } else {
                        Box(
                            modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)).background(certificateColor(model.certificate)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                model.certificate,
                                color = if (model.certificate == "PG") Color.Black else Color.White,
                                fontSize = if (model.certificate.length > 3) 28.sp else 44.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                is PreshowCardModel.FeaturePresentation -> {
                    Text("OUR FEATURE PRESENTATION", color = Color(0xFFBBBBBB), fontSize = 22.sp, letterSpacing = 4.sp)
                    Text(
                        model.title,
                        color = Color.White,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
