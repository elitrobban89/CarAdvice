package com.caradvice.scraper;

import com.caradvice.model.ExpertInsight;
import com.caradvice.service.ExpertInsightService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tester för Mobility Sweden-månadssynken: xlsx-parsning av rankingarken,
 * namnnormalisering, rapportupptäckt i HTML och ersättningslogiken.
 */
@ExtendWith(MockitoExtension.class)
class MobilityStatsSyncServiceTest {

    @Mock
    private ExpertInsightService insightService;

    private MobilityStatsSyncService service() {
        return new MobilityStatsSyncService(insightService);
    }

    // ── Testdata: bygger en xlsx i minnet med samma struktur som riktiga rapporten ──

    private static byte[] workbook(String period, Object[][] totalRows, Object[][] evRows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fillSheet(wb.createSheet("PB - Rankinglista"), period, totalRows);
            fillSheet(wb.createSheet("Elbil ranking"), period, evRows);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void fillSheet(Sheet s, String period, Object[][] rows) {
        s.createRow(1).createCell(7).setCellValue("Nyregistreringar " + period);
        Row head = s.createRow(7);
        head.createCell(3).setCellValue("Modell (Antal reg. YTD↓ )");
        head.createCell(12).setCellValue("Ackumulerat");
        int r = 9;
        for (Object[] row : rows) {
            Row dataRow = s.createRow(r++);
            dataRow.createCell(3).setCellValue((String) row[0]);
            dataRow.createCell(5).setCellValue((int) row[1]);
            dataRow.createCell(12).setCellValue((int) row[2]);
        }
    }

    // ── parseReport ──

    @Test
    void parsarLedareOchElbilsledareMedSvenskaAntal() throws Exception {
        byte[] xlsx = workbook("juni 2026",
                new Object[][]{{"VOLVO XC60", 2092, 7594}, {"VOLVO EX/XC40", 1261, 7389}},
                new Object[][]{{"VOLVO EX/XC40", 982, 6275}, {"TESLA MODEL Y", 1365, 4862}});

        List<ExpertInsight> insights = service().parseReport(xlsx);

        assertThat(insights).hasSize(2);
        assertThat(insights.get(0).getCarMake()).isEqualTo("Volvo");
        assertThat(insights.get(0).getCarModel()).isEqualTo("XC60");
        assertThat(insights.get(0).getInsight())
                .contains("januari–juni 2026").contains("7 594").contains("mest registrerade bil");
        assertThat(insights.get(1).getCarModel()).isEqualTo("EX40");
        assertThat(insights.get(1).getFuelType()).isEqualTo("elbil");
        assertThat(insights.get(1).getInsight()).contains("6 275").contains("före Tesla Model Y");
    }

    @Test
    void manadensEttaFarEgenRadNarDenSkiljerSigFranYtdLedaren() throws Exception {
        byte[] xlsx = workbook("juni 2026",
                new Object[][]{{"VOLVO XC60", 1000, 7594}, {"TESLA MODEL Y", 2500, 4862}},
                new Object[][]{{"VOLVO EX/XC40", 982, 6275}});

        List<ExpertInsight> insights = service().parseReport(xlsx);

        assertThat(insights).hasSize(3);
        assertThat(insights.get(2).getCarMake()).isEqualTo("Tesla");
        assertThat(insights.get(2).getInsight()).contains("juni 2026").contains("2 500");
    }

    @Test
    void saknatArkGerTomLista() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("Fel ark");
            wb.write(out);
            assertThat(service().parseReport(out.toByteArray())).isEmpty();
        }
    }

    // ── Namnnormalisering ──

    @Test
    void normaliserarRapportensVersalaModellnamn() {
        assertThat(MobilityStatsSyncService.normalizeName("VOLVO XC60")).containsExactly("Volvo", "XC60");
        assertThat(MobilityStatsSyncService.normalizeName("VOLVO EX/XC40")).containsExactly("Volvo", "EX40");
        assertThat(MobilityStatsSyncService.normalizeName("VW ID.7/ID.7 TOURER")).containsExactly("Volkswagen", "ID.7");
        assertThat(MobilityStatsSyncService.normalizeName("TESLA MODEL Y")).containsExactly("Tesla", "Model Y");
        assertThat(MobilityStatsSyncService.normalizeName("BMW IX3")).containsExactly("BMW", "IX3");
        assertThat(MobilityStatsSyncService.normalizeName("MERCEDES-BENZ CLA")).containsExactly("Mercedes", "CLA");
        assertThat(MobilityStatsSyncService.normalizeName("SKODA KODIAQ")).containsExactly("Skoda", "Kodiaq");
    }

    @Test
    void periodRangeSlarIhopJanuariTillAktuellManad() {
        assertThat(MobilityStatsSyncService.periodRange("juni 2026")).isEqualTo("januari–juni 2026");
        assertThat(MobilityStatsSyncService.periodRange("januari 2026")).isEqualTo("januari 2026");
        assertThat(MobilityStatsSyncService.periodRange(null)).isNull();
    }

    // ── Upptäckt ──

    @Test
    void extraherarArtikellankarForRattArIOrdningUtanDubbletter() {
        String html = """
                <a href="/statistik/Nyregistreringar_per_manad_1/nyregistreringar-2026/februari-rapport">feb</a>
                <a href="/statistik/Nyregistreringar_per_manad_1/nyregistreringar-2026/juli-rapport">jul</a>
                <a href="/statistik/Nyregistreringar_per_manad_1/nyregistreringar-2026/juli-rapport">jul igen</a>
                <a href="/statistik/Nyregistreringar_per_manad_1/nyregistreringar-2025/gammal">fel år</a>
                <a href="/om-oss">annat</a>
                """;
        List<String> urls = MobilityStatsSyncService.extractArticleUrls(html, 2026);
        assertThat(urls).containsExactly(
                "https://mobilitysweden.se/statistik/Nyregistreringar_per_manad_1/nyregistreringar-2026/februari-rapport",
                "https://mobilitysweden.se/statistik/Nyregistreringar_per_manad_1/nyregistreringar-2026/juli-rapport");
    }

    @Test
    void extraherarXlsxLankenMenIntePdf() {
        String html = """
                <a href="/storage/abc/pdf/media/x/Rapport.pdf">pdf</a>
                <a href="/storage/abc/xlsx/media/x/M%C3%A5nadsrapport%20Nyregistreringar%20juni%202026.xlsx">xlsx</a>
                """;
        assertThat(MobilityStatsSyncService.extractXlsxUrl(html))
                .isEqualTo("https://mobilitysweden.se/storage/abc/xlsx/media/x/M%C3%A5nadsrapport%20Nyregistreringar%20juni%202026.xlsx");
        assertThat(MobilityStatsSyncService.extractXlsxUrl("<a href=\"/x.pdf\">bara pdf</a>")).isNull();
    }

    // ── syncNow (nät stubbas via överlagring) ──

    private MobilityStatsSyncService stubbed(byte[] xlsx) {
        return new MobilityStatsSyncService(insightService) {
            @Override
            String findLatestReportUrl() {
                return xlsx == null ? null : "https://mobilitysweden.se/storage/rapport.xlsx";
            }

            @Override
            byte[] download(String url) {
                return xlsx;
            }
        };
    }

    @Test
    void syncNowErsatterManadsraderna() throws Exception {
        byte[] xlsx = workbook("juni 2026",
                new Object[][]{{"VOLVO XC60", 2092, 7594}},
                new Object[][]{{"VOLVO EX/XC40", 982, 6275}});

        Map<String, Object> result = stubbed(xlsx).syncNow();

        assertThat(result.get("status")).isEqualTo("OK");
        assertThat(result.get("imported")).isEqualTo(2);
        verify(insightService).deleteByExpert(MobilityStatsSyncService.EXPERT_NAME);
        verify(insightService, times(2)).save(any(ExpertInsight.class));
    }

    @Test
    void syncNowUtanRapportRorInteDatabasen() {
        Map<String, Object> result = stubbed(null).syncNow();

        assertThat(result.get("status")).isEqualTo("ERROR");
        verify(insightService, never()).deleteByExpert(any());
        verify(insightService, never()).save(any());
    }
}
