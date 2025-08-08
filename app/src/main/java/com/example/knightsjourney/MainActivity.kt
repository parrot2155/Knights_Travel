// MainActivity.kt
// Graph-based Knight's Journey: visit each node exactly once on an arbitrary graph.
// Jetpack Compose + Material3. Includes Undo/Reset/Auto-solve (DFS + Warnsdorff ordering).

package com.example.knightsjourney

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/* ----------------------------- Model ----------------------------- */

data class GNode(val id: Int, val x: Float, val y: Float) // normalized 0..1
data class GBoard(
    val name: String,
    val nodes: List<GNode>,
    val edges: Map<Int, Set<Int>> // adjacency (undirected)
)
data class GraphState(
    val board: GBoard,
    val path: List<Int>,
    val visited: Set<Int>
) {
    val moveCount get() = path.size
    val isComplete get() = moveCount == board.nodes.size
    fun last(): Int? = path.lastOrNull()
}

/* --------------------------- ViewModel --------------------------- */

class GraphViewModel : ViewModel() {

    private val presets: List<GBoard> = listOf(circle(8), star(10), ringWithChords(12))
    private val _state = mutableStateOf(GraphState(presets[0], emptyList(), emptySet()))
    val state: State<GraphState> = _state
    val allBoards get() = presets

    fun setBoard(board: GBoard) { _state.value = GraphState(board, emptyList(), emptySet()) }
    fun reset() { setBoard(_state.value.board) }

    fun undo() {
        val s = _state.value
        if (s.path.isEmpty()) return
        val np = s.path.dropLast(1)
        _state.value = s.copy(path = np, visited = np.toSet())
    }

    fun canMove(toId: Int): Boolean {
        val s = _state.value
        if (toId !in s.board.nodes.map { it.id }) return false
        if (toId in s.visited) return false
        val last = s.last() ?: return true
        return s.board.edges[last]?.contains(toId) == true
    }

    fun move(toId: Int) {
        if (!canMove(toId)) return
        val s = _state.value
        _state.value = s.copy(path = s.path + toId, visited = s.visited + toId)
    }

    /** DFS backtracking + Warnsdorff-like ordering. Small graphs solve fast. */
    suspend fun autoSolve(timeoutMs: Long = 2500L): Boolean = withContext(Dispatchers.Default) {
        val start = _state.value
        val n = start.board.nodes.size
        val board = start.board
        val startPath = if (start.path.isEmpty()) listOf(board.nodes.first().id) else start.path
        val visited0 = startPath.toSet()

        val deadline = System.currentTimeMillis() + timeoutMs
        var solution: List<Int>? = null

        fun dfs(path: MutableList<Int>, visited: HashSet<Int>): Boolean {
            if (System.currentTimeMillis() > deadline) return false
            if (path.size == n) { solution = path.toList(); return true }
            val u = path.last()
            val nexts = (board.edges[u] ?: emptySet())
                .filter { it !in visited }
                .sortedBy { v -> (board.edges[v] ?: emptySet()).count { it !in visited } }
            for (v in nexts) {
                path.add(v); visited.add(v)
                if (dfs(path, visited)) return true
                visited.remove(v); path.removeLast()
            }
            return false
        }

        val ok = dfs(startPath.toMutableList(), HashSet(visited0))
        if (ok && solution != null) {
            _state.value = GraphState(board, solution!!, solution!!.toSet()); true
        } else false
    }

    /* ------------------------ Board Presets ------------------------ */

    private fun circle(n: Int, r: Float = 0.42f, cx: Float = 0.5f, cy: Float = 0.5f): GBoard {
        val nodes = (0 until n).map { i ->
            val t = (2 * PI * i / n).toFloat()
            GNode(i, cx + r * cos(t), cy + r * sin(t))
        }
        val edges = nodes.associate { it.id to setOf((it.id + 1) % n, (it.id + n - 1) % n) }
        return GBoard("Circle-$n", nodes, edges)
    }

    private fun star(n: Int): GBoard {
        val outer = 0.44f; val inner = 0.22f; val cx = 0.5f; val cy = 0.5f
        val k = n / 2
        val nodes = buildList {
            repeat(k) { i ->
                val t = (2 * PI * i / k).toFloat()
                add(GNode(i, cx + outer * cos(t), cy + outer * sin(t)))
            }
            repeat(k) { i ->
                val t = (2 * PI * i / k).toFloat()
                add(GNode(k + i, cx + inner * cos(t + PI.toFloat() / k), cy + inner * sin(t + PI.toFloat() / k)))
            }
        }
        val edges = buildMap<Int, MutableSet<Int>> {
            fun add(a: Int, b: Int) { getOrPut(a){mutableSetOf()}.add(b); getOrPut(b){mutableSetOf()}.add(a) }
            repeat(k) { i -> add(i, (i + 1) % k) }             // outer ring
            repeat(k) { i -> add(k + i, k + (i + 1) % k) }     // inner ring
            repeat(k) { i -> add(i, k + i); add(i, k + ((i + 1) % k)); add((i + 2) % k, k + i) }
        }.mapValues { it.value.toSet() }
        return GBoard("Star-$n", nodes, edges)
    }

    private fun ringWithChords(n: Int): GBoard {
        val base = circle(n)
        val edges = base.edges.mapValues { it.value.toMutableSet() }.toMutableMap()
        for (i in 0 until n) { // long chords every third
            val a = i; val b = (i + 3) % n
            edges.getOrPut(a){mutableSetOf()}.add(b)
            edges.getOrPut(b){mutableSetOf()}.add(a)
        }
        return GBoard("Ring+Chords-$n", base.nodes, edges.mapValues { it.value.toSet() })
    }
}

/* ----------------------------- Activity ----------------------------- */

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    // ✅ onToast 파라미터를 넘기고, named arg 쓰지 말기
                    GraphJourneyScreen(
                        onToast = { msg ->
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

/* ------------------------------ UI ------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphJourneyScreen(onToast: (String) -> Unit, vm: GraphViewModel = viewModel()) {
    val state by vm.state
    val boards = vm.allBoards
    val scope = rememberCoroutineScope()
    var solving by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("게임 기사의 여행 — 그래프", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                TextField(
                    value = state.board.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("보드") },
                    modifier = Modifier.menuAnchor().width(200.dp)
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    boards.forEach { b ->
                        DropdownMenuItem(text = { Text(b.name) }, onClick = { vm.setBoard(b); expanded = false })
                    }
                }
            }

            AssistChip(onClick = { vm.reset() }, label = { Text("리셋") })
            AssistChip(onClick = { vm.undo() }, label = { Text("되돌리기") })
            AssistChip(
                onClick = {
                    if (solving) return@AssistChip
                    solving = true
                    scope.launch {
                        val ok = vm.autoSolve()
                        solving = false
                        if (!ok) onToast("시간 내 해답을 찾지 못했어요 😅")
                    }
                },
                label = { Text(if (solving) "자동 풀이 중…" else "자동 풀이") }
            )
        }

        StatsBar(state)

        GraphBoard(
            state = state,
            nodeRadius = 16.dp,
            onTapNode = { id -> if (vm.canMove(id)) vm.move(id) else onToast("이동 불가: 연결 없거나 이미 방문") }
        )

        if (state.isComplete) {
            Text("완료! 모든 노드를 한 번씩 방문 🎉", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun StatsBar(state: GraphState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("이동 수: ${state.moveCount}")
        val last = state.last()?.let { "#$it" } ?: "-"
        Text("현재: $last")
    }
}

@Composable
fun GraphBoard(
    state: GraphState,
    nodeRadius: Dp,
    onTapNode: (Int) -> Unit
) {
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val nodes = state.board.nodes
    val edges = state.board.edges

    val radiusPx = with(density) { nodeRadius.toPx() }

    // ✅ Compose 값은 여기서 미리 꺼내서 drawScope 안에서 사용
    val surfaceColor = MaterialTheme.colorScheme.surface
    val pathColor = MaterialTheme.colorScheme.primary
    val edgeColor = MaterialTheme.colorScheme.outline
    val visitedColor = MaterialTheme.colorScheme.primaryContainer
    val lastColor = MaterialTheme.colorScheme.tertiaryContainer
    val nodeColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val labelIdle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(surfaceColor)
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    // pick nearest node within threshold
                    val nearest = nodes.minByOrNull { n ->
                        val p = Offset(n.x * canvasSize.width, n.y * canvasSize.height)
                        hypot((p.x - pos.x).toDouble(), (p.y - pos.y).toDouble())
                    }
                    if (nearest != null) {
                        val p = Offset(nearest.x * canvasSize.width, nearest.y * canvasSize.height)
                        val d = hypot((p.x - pos.x).toDouble(), (p.y - pos.y).toDouble()).toFloat()
                        if (d <= radiusPx * 1.3f) onTapNode(nearest.id)
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            canvasSize = size

            // edges
            edges.forEach { (a, tos) ->
                val pa = nodes.first { it.id == a }
                val oa = Offset(pa.x * size.width, pa.y * size.height)
                tos.forEach { b ->
                    if (b < a) return@forEach  // avoid double draw
                    val pb = nodes.first { it.id == b }
                    val ob = Offset(pb.x * size.width, pb.y * size.height)
                    drawLine(edgeColor, oa, ob, strokeWidth = 2f, cap = StrokeCap.Round, pathEffect = PathEffect.cornerPathEffect(8f))
                }
            }

            // path
            if (state.path.size >= 2) {
                for (i in 0 until state.path.lastIndex) {
                    val a = nodes.first { it.id == state.path[i] }
                    val b = nodes.first { it.id == state.path[i + 1] }
                    val oa = Offset(a.x * size.width, a.y * size.height)
                    val ob = Offset(b.x * size.width, b.y * size.height)
                    drawLine(pathColor, oa, ob, strokeWidth = 6f, cap = StrokeCap.Round)
                }
            }

            // nodes
            nodes.forEach { n ->
                val o = Offset(n.x * size.width, n.y * size.height)
                val idx = state.path.indexOf(n.id)
                val isVisited = idx >= 0
                val isLast = state.last() == n.id
                val color = when {
                    isLast -> lastColor
                    isVisited -> visitedColor
                    else -> nodeColor
                }
                drawCircle(color, radiusPx, center = o)
                drawCircle(outlineColor, radiusPx, center = o, style = Stroke(width = 2f))
            }
        }

        // labels (Compose text on top)
        nodes.forEach { n ->
            val idx = state.path.indexOf(n.id)
            val isVisited = idx >= 0
            val isLast = state.last() == n.id
            val left = with(density) { (n.x * canvasSize.width).toDp() } - nodeRadius
            val top  = with(density) { (n.y * canvasSize.height).toDp() } - nodeRadius
            Box(
                Modifier
                    .offset(x = left, y = top)
                    .size(nodeRadius * 2)
                    .background(color = androidx.compose.ui.graphics.Color.Transparent, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isVisited) {
                    Text(
                        text = "${idx + 1}",
                        fontSize = 16.sp,
                        fontWeight = if (isLast) FontWeight.ExtraBold else FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(text = "${n.id}", fontSize = 10.sp, color = labelIdle)
                }
            }
        }
    }
}
