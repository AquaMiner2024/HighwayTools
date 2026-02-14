package trombone

import HighwayTools.moveSpeed
import HighwayTools.scaffold
import HighwayTools.rangeMultiplier
import HighwayTools.waitTicks
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.BaritoneUtils
import com.lambda.client.util.EntityUtils.flooredPosition
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.math.Direction
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.math.VectorUtils.multiply
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import com.lambda.client.util.world.isReplaceable
import net.minecraft.block.BlockLiquid
import net.minecraft.init.Blocks
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import trombone.IO.disableError
import trombone.Statistics.simpleMovingAverageDistance
import trombone.Trombone.active
import trombone.handler.Container.containerTask
import trombone.handler.Container.getCollectingPosition
import trombone.handler.Inventory.lastHitVec
import trombone.task.TaskManager.isBehindPos
import trombone.task.TaskManager.populateTasks
import trombone.task.TaskManager.tasks
import trombone.task.TaskState

object Pathfinder {
    var goal: BlockPos? = null
    var moveState = MovementState.RUNNING

    val rubberbandTimer = TickTimer(TimeUnit.TICKS)

    var startingDirection = Direction.NORTH
    var currentBlockPos = BlockPos(0, -1, 0)
    var startingBlockPos = BlockPos(0, -1, 0)
    private var targetBlockPos = BlockPos(0, -1, 0)
    var distancePending = 0

    private var diagonalTarget1: Vec3d? = null
    private var diagonalTarget2: Vec3d? = null
    private var diagonalStep = 0
    private var diagonalWaitTicks = 0

    enum class MovementState {
        RUNNING, PICKUP, BRIDGE, RESTOCK, DIAGONAL_STUCK
    }

    fun SafeClientEvent.setupPathing() {
        moveState = MovementState.RUNNING
        startingBlockPos = player.flooredPosition
        currentBlockPos = startingBlockPos
        startingDirection = Direction.fromEntity(player)
    }

    fun SafeClientEvent.updatePathing() {
        when (moveState) {
            MovementState.RUNNING -> {
                goal = currentBlockPos
                diagonalTarget1 = null
                diagonalTarget2 = null

                // ToDo: Rewrite
                if (currentBlockPos.distanceTo(targetBlockPos) < 2 ||
                    (distancePending > 0 &&
                        startingBlockPos.add(
                            startingDirection.directionVec.multiply(distancePending)
                        ).distanceTo(currentBlockPos) == 0.0)) {
                    disableError("Reached target destination")
                    return
                }

                val possiblePos = currentBlockPos.add(startingDirection.directionVec)

                if (!isTaskDone(possiblePos.up())
                    || !isTaskDone(possiblePos)
                    || !isTaskDone(possiblePos.down())
                ) return

                if (!checkForResidue(possiblePos.up())) return

                if (world.getBlockState(possiblePos.down()).isReplaceable) return

                if (currentBlockPos != possiblePos
                    && player.positionVector.distanceTo(currentBlockPos.toVec3dCenter()) < 2
                ) {
                    simpleMovingAverageDistance.add(System.currentTimeMillis())
                    lastHitVec = Vec3d.ZERO
                    currentBlockPos = possiblePos
                    populateTasks()
                }
            }
            MovementState.BRIDGE -> {
                goal = null
                val isAboveAir = world.getBlockState(player.flooredPosition.down()).isReplaceable
                if (isAboveAir) player.movementInput?.sneak = true
                if (shouldBridge()) {
                    // Find nearest gap
                    var nearestGap: BlockPos? = null
                    for (i in 1..3) {
                        val checkPos = currentBlockPos.add(startingDirection.directionVec.multiply(i))
                        if (world.isAirBlock(checkPos) && world.getBlockState(checkPos.down()).isReplaceable) {
                            nearestGap = checkPos
                            break
                        }
                    }
                    // Move to nearest gap center
                    nearestGap?.let { gap ->
                        val target = gap.toVec3dCenter()
                        moveTo(target)
                    }
                } else {
                    if (!isAboveAir) {
                        moveState = MovementState.RUNNING
                    }
                }
            }
            MovementState.PICKUP -> {
                goal = getCollectingPosition()
            }
            MovementState.RESTOCK -> {
                val target = currentBlockPos.toVec3dCenter()
                if (player.positionVector.distanceTo(target) < 2) {
                    goal = null
                    moveTo(target)
                } else {
                    goal = currentBlockPos
                }
            }
            MovementState.DIAGONAL_STUCK -> {
                goal = null
                val dir = startingDirection.directionVec
                val dx = dir.x.toDouble()
                val dz = dir.z.toDouble()
                if (diagonalWaitTicks > 0) {
                    diagonalWaitTicks--
                    stopMoveTo()
                    return
                }
                if (diagonalTarget1 == null) {
                    diagonalTarget1 = Vec3d(player.posX + (-dx - dz) * rangeMultiplier, player.posY, player.posZ + (-dz + dx) * rangeMultiplier)
                    diagonalTarget2 = Vec3d(player.posX + (-dx + dz) * rangeMultiplier, player.posY, player.posZ + (-dz - dx) * rangeMultiplier)
                }
                val t1 = diagonalTarget1!!
                val t2 = diagonalTarget2!!
                when (diagonalStep) {
                    0 -> {
                        moveTo(t1)
                        if (player.positionVector.distanceTo(t1) < 0.3) {
                            stopMoveTo()
                            diagonalStep = 1
                            diagonalWaitTicks = waitTicks
                        }
                    }
                    1 -> {
                        moveTo(t2)
                        if (player.positionVector.distanceTo(t2) < 0.3) {
                            stopMoveTo()
                            diagonalTarget1 = null
                            diagonalTarget2 = null
                            diagonalStep = 0
                            diagonalWaitTicks = waitTicks
                            moveState = MovementState.RUNNING
                        }
                    }
                }
            }
        }
    }

    private fun checkForResidue(pos: BlockPos) =
        containerTask.taskState == TaskState.DONE
            && tasks.values.all {
                it.taskState == TaskState.DONE || !isBehindPos(pos, it.blockPos)
            }

    private fun SafeClientEvent.isTaskDone(pos: BlockPos): Boolean {
        val block = world.getBlockState(pos).block
        return tasks[pos]?.let {
            it.taskState == TaskState.DONE
                && block != Blocks.PORTAL
                && block != Blocks.END_PORTAL
                && block !is BlockLiquid
        } ?: false
    }

    fun SafeClientEvent.shouldBridge(): Boolean {
        if (!scaffold || containerTask.taskState != TaskState.DONE) return false
        if (tasks.values.filter { it.taskState == TaskState.PLACE || it.taskState == TaskState.LIQUID }.any { it.sequence.isNotEmpty() }) return false

        // Check front 3 blocks
        for (i in 1..3) {
            val checkPos = currentBlockPos.add(startingDirection.directionVec.multiply(i))
            if (world.isAirBlock(checkPos) && world.getBlockState(checkPos.down()).isReplaceable) {
                return true
            }
        }
        return false
    }

    private fun SafeClientEvent.moveTo(target: Vec3d) {
        player.motionX = (target.x - player.posX).coerceIn((-moveSpeed).toDouble(), moveSpeed.toDouble())
        player.motionZ = (target.z - player.posZ).coerceIn((-moveSpeed).toDouble(), moveSpeed.toDouble())
    }

    private fun SafeClientEvent.stopMoveTo() {
        player.motionX = 0.0
        player.motionZ = 0.0
    }

    fun updateProcess() {
        if (!active) {
            active = true
            BaritoneUtils.primary?.pathingControlManager?.registerProcess(Process)
        }
    }

    fun clearProcess() {
        active = false
        goal = null
    }
}