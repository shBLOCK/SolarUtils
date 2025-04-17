package dynamics

import de.fabmax.kool.math.MutableQuatD
import de.fabmax.kool.math.MutableVec3d
import universe.CelestialBody
import utils.IntFract

class TransformedDynModel(
    private val transform: Transform,
    private val delegate: CelestialDynModel
) : CelestialDynModel by delegate {

    private var dirty = true
    override fun seek(time: IntFract) {
        delegate.seek(time)
        dirty = true
    }

    private val _position = MutableVec3d()
    private val _orientation = MutableQuatD()

    private fun update() {
        if (dirty) {
            delegate.position(_position)
            delegate.orientation(_orientation)
            transform(_position, _orientation)
            dirty = false
        }
    }

    override fun position(result: MutableVec3d) = update().let { result.set(_position) }
    override fun orientation(result: MutableQuatD) = update().let { result.set(_orientation) }

    override fun copy() = TransformedDynModel(transform, delegate.copyTyped())

    typealias Transform = (position: MutableVec3d, orientation: MutableQuatD) -> Unit
}

fun CelestialDynModel.transformed(transform: TransformedDynModel.Transform) =
    TransformedDynModel(transform, this)

class TransformedUniverseDynModel(
    private val transform: TransformedDynModel.Transform,
    private val delegate: UniverseDynModelImpl
) : UniverseDynModelImpl() {
    override fun getDynModelFor(celestialBody: CelestialBody): CelestialDynModel? =
        delegate.getDynModelFor(celestialBody)?.transformed(transform)

    override fun copy() = TransformedUniverseDynModel(transform, delegate.copyTyped())

    override fun seek(time: IntFract) {
        delegate.seek(time)
        super.seek(time)
    }
}

fun UniverseDynModelImpl.transformed(transform: TransformedDynModel.Transform) =
    TransformedUniverseDynModel(transform, this)