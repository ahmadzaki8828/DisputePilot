// AG Studio theme for /analytics. Every value points at a CSS variable from ./analytics.css, which follows the
// app's light and dark colours, so Studio's widgets, grids and charts read as part of the DisputePilot page.
import { studioTheme } from "ag-studio";

const token = (name: string) => `var(--dpa-${name})`;
const line = (color: string) => ({ width: 1, color: token(color) });

const palette = Object.fromEntries(
  Array.from({ length: 8 }, (_, i) => [
    [`chartPaletteFills${i + 1}Color`, token(`chart-${i + 1}`)],
    [`chartPaletteStrokes${i + 1}Color`, token(`chart-${i + 1}`)],
  ]).flat(),
);

export const disputePilotStudioTheme = studioTheme.withParams({
  fontFamily: token("font"),
  fontSize: 14,
  textColor: token("ink"),
  foregroundColor: token("ink"),
  subtleTextColor: token("muted"),
  backgroundColor: token("surface"),
  borderColor: token("border"),
  accentColor: token("accent"),
  iconColor: token("ink"),
  rowHoverColor: token("highlight"),

  studioWrapperBorder: false,
  studioWrapperBorderRadius: 0,
  studioWrapperSpacing: 0,
  studioWrapperBackgroundColor: token("canvas"),
  studioCanvasBorder: false,
  studioCanvasBorderRadius: 0,
  studioCanvasShadow: false,
  studioCanvasBackgroundColor: token("canvas"),
  studioCanvasPadding: 12,
  studioCanvasRowHeight: 18,
  studioCanvasFontFamily: token("font"),

  studioWidgetBackgroundColor: token("surface"),
  studioWidgetBorder: line("border"),
  studioWidgetBorderRadius: 8,
  studioWidgetPadding: 10,
  studioWidgetTitleFontFamily: token("font"),
  studioWidgetTitleFontSize: 14,
  studioWidgetTitleFontWeight: 600,
  studioWidgetTitleTextColor: token("ink"),
  studioWidgetSubtitleFontFamily: token("font"),
  studioWidgetSubtitleTextColor: token("muted"),
  studioWidgetCaptionFontFamily: token("font"),
  studioWidgetCaptionTextColor: token("muted"),
  studioWidgetResizeBorderColor: token("accent"),

  studioPanelContainerBackgroundColor: token("surface"),
  studioPanelContainerBorder: line("border"),
  studioPanelDividerColor: token("border"),
  studioPanelDividerActiveColor: token("accent"),
  studioPanelSectionBorderColor: token("border"),
  studioPanelHeaderFontFamily: token("font"),
  studioPanelHeaderFontWeight: 600,
  studioToggleButtonActiveBackgroundColor: token("highlight"),
  studioToggleButtonActiveBorderColor: token("accent"),
  studioToggleButtonActiveColor: token("accent"),

  gridFontFamily: token("font"),
  gridFontSize: 13,
  gridCellTextColor: token("ink"),
  gridHeaderBackgroundColor: token("surface"),
  gridHeaderTextColor: token("muted"),
  gridHeaderFontWeight: 600,
  gridRowHeight: 36,
  gridRowBorder: line("gridline"),
  gridHeaderRowBorder: line("border"),
  gridRowHoverColor: token("highlight"),
  gridWrapperBorder: false,

  chartFontFamily: token("font"),
  chartTextColor: token("ink"),
  chartSubtleTextColor: token("muted"),
  chartAxisLineColor: token("border"),
  chartGridLineColor: token("gridline"),
  ...palette,
});
