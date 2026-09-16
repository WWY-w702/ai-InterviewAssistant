package com.faceai.pdfreader.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.faceai.pdfreader.repository.EloRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EloRatingServiceTest {

    @Mock
    private EloRepository eloRepository;

    @InjectMocks
    private EloRatingService eloRatingService;

    @Test
    void getUserRating_returnsDefault1200_whenNotExists() {
        when(eloRepository.findUserRating(1L, "后端开发")).thenReturn(Optional.empty());

        double rating = eloRatingService.getUserRating(1L, "后端开发");

        assertEquals(1200.0, rating);
    }

    @Test
    void getUserRating_returnsStoredRating() {
        when(eloRepository.findUserRating(1L, "后端开发")).thenReturn(Optional.of(1350.0));

        double rating = eloRatingService.getUserRating(1L, "后端开发");

        assertEquals(1350.0, rating);
    }

    @Test
    void selectDifficultyLabel_mapsCorrectly() {
        assertEquals("简单", eloRatingService.selectDifficultyLabel(1000));
        assertEquals("简单", eloRatingService.selectDifficultyLabel(1099));
        assertEquals("标准", eloRatingService.selectDifficultyLabel(1100));
        assertEquals("标准", eloRatingService.selectDifficultyLabel(1299));
        assertEquals("困难", eloRatingService.selectDifficultyLabel(1300));
        assertEquals("困难", eloRatingService.selectDifficultyLabel(1499));
        assertEquals("专家", eloRatingService.selectDifficultyLabel(1500));
        assertEquals("专家", eloRatingService.selectDifficultyLabel(1800));
    }

    @Test
    void selectAdaptiveDifficulty_returnsStandard_whenColdStart() {
        when(eloRepository.findUserGamesPlayed(1L, "后端开发")).thenReturn(2);

        String difficulty = eloRatingService.selectAdaptiveDifficulty(1L, "后端开发");

        assertEquals("标准", difficulty);
    }

    @Test
    void selectAdaptiveDifficulty_returnsBasedOnElo_whenWarmedUp() {
        when(eloRepository.findUserGamesPlayed(1L, "后端开发")).thenReturn(5);
        when(eloRepository.findUserRating(1L, "后端开发")).thenReturn(Optional.of(1400.0));

        String difficulty = eloRatingService.selectAdaptiveDifficulty(1L, "后端开发");

        assertEquals("困难", difficulty);
    }

    @Test
    void updateAfterQuestion_calculatesEloCorrectly() {
        when(eloRepository.findUserRating(1L, "后端开发")).thenReturn(Optional.of(1200.0));
        // questionId 为 null 时 updateAfterQuestion 走短路分支，题目难度直接取 1200.0，
        // 不会查库 —— 所以这里不能 stub findQuestionDifficulty，否则是无效 stub。

        double newElo = eloRatingService.updateAfterQuestion(1L, "session1", 1, null, 80, "后端开发");

        // Score 80/100 = 0.8, expected = 0.5 (equal ratings)
        // newElo = 1200 + 32 * (0.8 - 0.5) = 1200 + 9.6 = 1209.6
        assertTrue(newElo > 1200, "Elo should increase after scoring 80");
        assertTrue(newElo < 1220, "Elo increase should be reasonable");

        verify(eloRepository).upsertUserRating(1L, "后端开发", newElo);
        verify(eloRepository).saveTrajectory(anyLong(), anyString(), anyInt(),
                anyDouble(), anyDouble(), anyInt(), anyDouble(), anyString());
    }

    @Test
    void updateAfterQuestion_decreasesElo_whenLowScore() {
        when(eloRepository.findUserRating(1L, "后端开发")).thenReturn(Optional.of(1200.0));
        // 同上：questionId 为 null，题目难度走短路分支取 1200.0，无需 stub。

        double newElo = eloRatingService.updateAfterQuestion(1L, "session1", 1, null, 30, "后端开发");

        // Score 30/100 = 0.3, expected = 0.5
        // newElo = 1200 + 32 * (0.3 - 0.5) = 1200 - 6.4 = 1193.6
        assertTrue(newElo < 1200, "Elo should decrease after scoring 30");
    }
}
