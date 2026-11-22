package com.byd.tripstats.data.preferences

/**
 * Dashboard layout variants.
 *
 * [CLASSIC] is the original chart-centric layout available to everyone.
 * [CARDS] is the Pro-only redesign: no central chart, larger separated tiles
 * with a user-customisable (show/hide + drag-to-reorder) middle section.
 */
enum class DashboardLayout { CLASSIC, CARDS }

/**
 * Which side of a [DashboardLayout.CLASSIC] screen the range chart and trip bar take; the vehicle
 * cards fill the other. Stored by reading direction, as Compose lays a Row out: [START] is the left
 * in a left-to-right language and the right in Hebrew, so the default keeps every install where it
 * was. Settings names the sides Left and Right as they fall on the screen.
 */
enum class ClassicChartSide { START, END }

/**
 * How a full-width [DashboardLayout.CLASSIC] screen sets the vehicle cards beside the range chart:
 * [TWO] columns of larger cards (the default since 2.21), or [ONE] narrow column of compact cards,
 * as up to 2.20, which leaves the chart about 85 % of the width. Split screen and a phone always
 * use one column.
 */
enum class ClassicCardColumns { ONE, TWO }
