package com.bazel_diff.bazel

import assertk.assertThat
import assertk.assertions.containsAll
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEqualTo
import com.bazel_diff.hash.TargetHash
import com.bazel_diff.interactor.CalculateImpactedTargetsInteractor
import com.bazel_diff.testModule
import java.io.StringWriter
import org.junit.Rule
import org.junit.Test
import org.koin.test.KoinTest
import org.koin.test.KoinTestRule

/**
 * Reproduction for the 17.0.1 -> 18.1.0 upgrade regression.
 *
 * PR #330 (commit b213ee4) changed `BazelModService.getModuleGraphJson()` from
 *   stderr = Redirect.CAPTURE
 * to
 *   stderr = Redirect.SILENT.
 *
 * Because `Process.kt` merges stderr into stdout via `ProcessBuilder.redirectErrorStream(true)`
 * whenever both streams are CAPTURE, every `moduleGraphJson` produced by bazel-diff 17.0.1
 * through 18.0.5 is a concatenation of whatever bazel wrote to stderr ("INFO: Checking for
 * file changes...", "INFO: Invocation ID: ...", etc.) and the JSON graph.
 *
 * When a CI pipeline re-uses a base graph produced by 18.0.x and compares it against a head
 * graph produced by 18.1.0, `CalculateImpactedTargetsInteractor.execute()` reaches
 * `moduleGraphParser.parseModuleGraph(fromGraph)`. Gson throws on the non-JSON prefix, the
 * broad try/catch at ModuleGraphParser.kt:36-39 returns `emptyMap()`, and
 * `findChangedModules(emptyMap(), fullMap)` then reports every single module as "added".
 *
 * The downstream effect — not exercised by this unit test because it requires a real
 * BazelQueryService — is that `queryTargetsDependingOnModules` spawns one `bazel query
 * rdeps(...)` subprocess per substring-matching external repo, typically thousands, turning
 * a single `get-impacted-targets` invocation into a multi-hour serial fan-out.
 *
 * These tests assert the upper half of the chain: stderr-polluted input -> empty parse ->
 * "every module changed". They will start failing (in a good way) as soon as upstream
 * makes parseModuleGraph robust to the non-JSON prefix or makes findChangedModules bail out
 * when one side parses to empty.
 */
class StderrPollutionRegressionTest : KoinTest {
  @get:Rule val koinTestRule = KoinTestRule.create { modules(testModule()) }

  private val parser = ModuleGraphParser()

  /**
   * Exact shape of stderr lines we observed in BuildBuddy logs. The leading "INFO:" lines
   * are emitted by Bazel on every `bazel mod graph --output=json` invocation and were
   * captured into `moduleGraphJson` by 18.0.x's CAPTURE/CAPTURE configuration.
   */
  private val stderrPrefix =
      """
      Computing main repo mapping:
      Loading:
      Loading: 0 packages loaded
      Analyzing: 0 targets (0 packages loaded, 0 targets configured)
      INFO: Invocation ID: 4d8d5c62-1f1c-4f72-9a3e-5fbd5e6ac3d2
      INFO: Current date is 2026-04-20
      """.trimIndent()

  /** Minimal but realistic module graph JSON, shaped like `bazel mod graph --output=json`. */
  private val cleanGraphJson =
      """
      {
        "key": "<root>",
        "name": "my-workspace",
        "version": "",
        "apparentName": "my-workspace",
        "dependencies": [
          {"key": "bazel_tools@_", "name": "bazel_tools", "version": "_", "apparentName": "bazel_tools"},
          {"key": "abseil-cpp@20240116.2", "name": "abseil-cpp", "version": "20240116.2", "apparentName": "com_google_absl"},
          {"key": "aspect_bazel_lib@2.22.5", "name": "aspect_bazel_lib", "version": "2.22.5", "apparentName": "aspect_bazel_lib"},
          {"key": "rules_jvm_external@6.10", "name": "rules_jvm_external", "version": "6.10", "apparentName": "rules_jvm_external"},
          {"key": "rules_python@1.8.4", "name": "rules_python", "version": "1.8.4", "apparentName": "rules_python"},
          {"key": "googletest@1.14.0", "name": "googletest", "version": "1.14.0", "apparentName": "com_google_googletest"}
        ]
      }
      """.trimIndent()

  /**
   * What a base graph produced by bazel-diff 17.0.1..18.0.5 actually contained.
   *
   * `getModuleGraphJson()` at those versions called `process(..., stdout = Redirect.CAPTURE,
   * stderr = Redirect.CAPTURE)`. In Process.kt, `captureAll = stdout == stderr && stderr ==
   * CAPTURE` is true, which triggers `redirectErrorStream(true)` at the ProcessBuilder level,
   * physically interleaving stderr into stdout. The final `result.output.joinToString("\n")`
   * therefore returns "<bazel stderr lines>\n<JSON graph>".
   */
  private val pollutedGraphJson = "$stderrPrefix\n$cleanGraphJson"

  @Test
  fun `18_0_x polluted moduleGraphJson fails to parse and returns empty map`() {
    // This is the critical step. If parseModuleGraph returned even a partial result here,
    // findChangedModules would correctly report no changes when comparing the same graph
    // against itself. Because it returns emptyMap() instead, the asymmetry between
    // emptyMap and a fully-parsed clean graph is what drives every module into "added".
    val result = parser.parseModuleGraph(pollutedGraphJson)

    assertThat(result).isEmpty()
  }

  @Test
  fun `18_1_0 clean moduleGraphJson parses successfully`() {
    val result = parser.parseModuleGraph(cleanGraphJson)

    // Root + 6 deps.
    assertThat(result).hasSize(7)
  }

  @Test
  fun `polluted from 18_0_x vs clean from 18_1_0 reports every module as changed`() {
    // Simulates the CI comparison: base graph was produced by bazel-diff 18.0.x (polluted),
    // head graph by bazel-diff 18.1.0 (clean). Even if the underlying MODULE.bazel is
    // identical, this produces a spurious "every module added" result.
    val fromGraph = parser.parseModuleGraph(pollutedGraphJson)
    val toGraph = parser.parseModuleGraph(cleanGraphJson)

    val changed = parser.findChangedModules(fromGraph, toGraph)

    // Every single module in the new graph is reported as changed, despite the underlying
    // module set being identical between base and head. This is what gets fed into
    // `queryTargetsDependingOnModules`, producing the rdeps fan-out.
    assertThat(changed).hasSize(7)
    assertThat(changed).containsAll(
        "<root>",
        "bazel_tools@_",
        "abseil-cpp@20240116.2",
        "aspect_bazel_lib@2.22.5",
        "rules_jvm_external@6.10",
        "rules_python@1.8.4",
        "googletest@1.14.0",
    )
  }

  @Test
  fun `string compare at line 44 fires because polluted != clean even when modules are identical`() {
    // CalculateImpactedTargetsInteractor.execute() line 44:
    //   val moduleGraphChanged = fromModuleGraphJson != toModuleGraphJson
    // This is a naive string compare. Even when the parsed semantic content is identical,
    // stderr pollution makes the raw strings differ, which gates into the expensive
    // `queryTargetsDependingOnModules` branch instead of the fast `computeSimpleImpactedTargets`.
    assertThat(pollutedGraphJson).isNotEqualTo(cleanGraphJson)
  }

  @Test
  fun `fan-out size scales with number of modules in head graph`() {
    // Upstream workspaces tend to have ~100 declared bzlmod deps. We simulate that here to
    // show that the "every module reported changed" count is bounded by the size of the
    // head-side graph, not by what actually changed in MODULE.bazel.
    val deps = (1..100).joinToString(",\n") { i ->
      """          {"key": "mod$i@1.0", "name": "mod$i", "version": "1.0", "apparentName": "mod$i"}"""
    }
    val bigHeadGraph = """
      {
        "key": "<root>",
        "name": "my-workspace",
        "version": "",
        "apparentName": "my-workspace",
        "dependencies": [
$deps
        ]
      }
      """.trimIndent()
    val bigPolluted = "$stderrPrefix\n$bigHeadGraph"

    val fromGraph = parser.parseModuleGraph(bigPolluted)
    val toGraph = parser.parseModuleGraph(bigHeadGraph)
    val changed = parser.findChangedModules(fromGraph, toGraph)

    // 100 deps + 1 root. Zero actually changed semantically, but every one is reported.
    assertThat(changed.size).isGreaterThan(50)
    assertThat(changed).hasSize(101)
  }

  @Test
  fun `end-to-end - semantically identical graph compared across versions marks every target as impacted`() {
    // End-to-end demonstration via CalculateImpactedTargetsInteractor.execute.
    //
    // Scenario: the workspace content is totally unchanged between base and head. Even the
    // user-visible module graph is identical. The *only* difference between the two inputs
    // is that the base was captured with stderr pollution (bazel-diff 18.0.x) and the head
    // was captured cleanly (18.1.0).
    //
    // With no BazelQueryService registered in Koin, `queryTargetsDependingOnModules`
    // conservatively reports `allTargets.keys` as impacted (see line 278-280 of
    // CalculateImpactedTargetsInteractor.kt). In a real CI run this instead spawns ~5,000
    // `bazel query rdeps(...)` subprocesses over ~3 hours. Both collapse a zero-change
    // workspace into "everything changed".
    val hashes = mapOf(
        "//:target1" to TargetHash("", "unchanged1", "unchanged1"),
        "//:target2" to TargetHash("", "unchanged2", "unchanged2"),
        "//:target3" to TargetHash("", "unchanged3", "unchanged3"),
    )

    val outputWriter = StringWriter()
    CalculateImpactedTargetsInteractor().execute(
        from = hashes,
        to = hashes,
        outputWriter = outputWriter,
        targetTypes = null,
        fromModuleGraphJson = pollutedGraphJson,
        toModuleGraphJson = cleanGraphJson,
    )

    val impacted = outputWriter.toString().trim().split("\n").filter { it.isNotEmpty() }
    // Every workspace target is reported as impacted despite zero semantic change.
    assertThat(impacted).containsExactlyInAnyOrder("//:target1", "//:target2", "//:target3")
  }

  @Test
  fun `end-to-end - same bazel-diff version on both sides is fast path`() {
    // Counter-example: when both base and head are captured by the same bazel-diff version,
    // the raw JSON strings are byte-equal, `moduleGraphChanged = false`, and
    // `computeSimpleImpactedTargets` runs. Zero hash changes -> zero impacted targets.
    val hashes = mapOf(
        "//:target1" to TargetHash("", "unchanged1", "unchanged1"),
        "//:target2" to TargetHash("", "unchanged2", "unchanged2"),
    )

    val outputWriter = StringWriter()
    CalculateImpactedTargetsInteractor().execute(
        from = hashes,
        to = hashes,
        outputWriter = outputWriter,
        targetTypes = null,
        fromModuleGraphJson = cleanGraphJson,
        toModuleGraphJson = cleanGraphJson,
    )

    val impacted = outputWriter.toString().trim().split("\n").filter { it.isNotEmpty() }
    assertThat(impacted).isEmpty()
  }
}
