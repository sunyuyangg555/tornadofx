@file:Suppress("unused")

package tornadofx

import javafx.beans.Observable
import javafx.beans.binding.Bindings
import javafx.beans.binding.BooleanBinding
import javafx.beans.binding.BooleanExpression
import javafx.beans.property.*
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.collections.ObservableMap
import javafx.collections.ObservableSet
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.paint.Paint
import tornadofx.FX.Companion.runAndWait
import java.time.LocalDate
import java.util.*
import java.util.concurrent.Callable
import kotlin.collections.ArrayList
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1

val viewModelBundle: ResourceBundle = ResourceBundle.getBundle("tornadofx/i18n/ViewModel")

open class ViewModel : Component(), ScopedInstance {
    val propertyMap: ObservableMap<Property<*>, () -> Property<*>?> = FXCollections.observableHashMap<Property<*>, () -> Property<*>?>()
    val propertyCache: ObservableMap<Property<*>, Property<*>> = FXCollections.observableHashMap<Property<*>, Property<*>>()
    val externalChangeListeners: ObservableMap<Property<*>, ChangeListener<Any>> = FXCollections.observableHashMap<Property<*>, ChangeListener<Any>>()
    val dirtyProperties: ObservableList<ObservableValue<*>> = FXCollections.observableArrayList<ObservableValue<*>>()
    open val dirty = booleanBinding(dirtyProperties, dirtyProperties) { isNotEmpty() }
    @Deprecated("Use dirty property instead", ReplaceWith("dirty"))
    fun dirtyStateProperty() = dirty

    val validationContext = ValidationContext()
    val ignoreDirtyStateProperties = FXCollections.observableArrayList<ObservableValue<out Any>>()
    val autocommitProperties = FXCollections.observableArrayList<ObservableValue<out Any>>()

    companion object {
        val propertyToViewModel = WeakHashMap<Observable, ViewModel>()
        val propertyToFacade = WeakHashMap<Observable, Property<*>>()
        fun getViewModelForProperty(property: Observable): ViewModel? = propertyToViewModel[property]
        fun getFacadeForProperty(property: Observable): Property<*>? = propertyToFacade[property]

        /**
         * Register the combination of a property that has been bound to a property
         * that might be a facade in a ViewModel. This is done to be able to locate
         * the validation context for this binding.
         */
        fun register(property: ObservableValue<*>, possiblyFacade: ObservableValue<*>?) {
            val propertyOwner = (possiblyFacade as? Property<*>)?.bean as? ViewModel
            if (propertyOwner != null) {
                propertyToFacade[property] = possiblyFacade as Property<*>
                propertyToViewModel[property] = propertyOwner
            }
        }
    }

    init {
        autocommitProperties.onChange {
            while (it.next()) {
                if (it.wasAdded()) {
                    it.addedSubList.forEach { facade ->
                        facade.addListener { obs, _, nv ->
                            if (validate(fields = facade)) propertyMap[obs]!!.invoke()?.value = nv
                        }
                    }
                }
            }
        }
    }

    /**
     * Wrap a JavaFX property and return the ViewModel facade for this property
     *
     * The value is returned in a lambda so that you can swap source objects
     * and call rebind to change the underlying source object in the mappings.
     *
     * You can bind a facade towards any kind of property as long as it can
     * be converted to a JavaFX property. TornadoFX provides a way to support
     * most property types via a concise syntax, see below for examples.
     * ```
     * class PersonViewModel(var person: Person) : ViewModel() {
     *     // Bind JavaFX property
     *     val name = bind { person.nameProperty() }
     *
     *     // Bind Kotlin var based property
     *     val name = bind { person.observable(Person::name) }
     *
     *     // Bind Java POJO getter/setter
     *     val name = bind { person.observable(Person::getName, Person::setName) }
     *
     *     // Bind Java POJO by property name (not type safe)
     *     val name = bind { person.observable("name") }
     * }
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    inline fun <reified PropertyType : Property<T>, reified T : Any, ResultType : PropertyType> bind(autocommit: Boolean = false, forceObjectProperty: Boolean = false, noinline propertyProducer: () -> PropertyType?): ResultType {
        val prop = propertyProducer()

        val facade: Property<*>

        if (forceObjectProperty) {
            facade = BindingAwareSimpleObjectProperty<T>(this, prop?.name)
        } else {
            val propertyType = PropertyType::class.java
            val typeParam = T::class.java

            // Match PropertyType against known Property types first
            if (IntegerProperty::class.java.isAssignableFrom(propertyType))
                facade = BindingAwareSimpleIntegerProperty(this, prop?.name)
            else if (LongProperty::class.java.isAssignableFrom(propertyType))
                facade = BindingAwareSimpleLongProperty(this, prop?.name)
            else if (DoubleProperty::class.java.isAssignableFrom(propertyType))
                facade = BindingAwareSimpleDoubleProperty(this, prop?.name)
            else if (FloatProperty::class.java.isAssignableFrom(propertyType))
                facade = BindingAwareSimpleFloatProperty(this, prop?.name)
            else if (BooleanProperty::class.java.isAssignableFrom(propertyType))
                facade = BindingAwareSimpleBooleanProperty(this, prop?.name)
            else if (StringProperty::class.java.isAssignableFrom(propertyType))
                facade = BindingAwareSimpleStringProperty(this, prop?.name)
            else if (ObservableList::class.java.isAssignableFrom(propertyType))
                facade = BindingAwareSimpleListProperty<T>(this, prop?.name)
            else if (List::class.java.isAssignableFrom(propertyType))
                facade = BindingAwareSimpleListProperty<T>(this, prop?.name)
            else if (ObservableSet::class.java.isAssignableFrom(propertyType))
                facade = BindingAwareSimpleSetProperty<T>(this, prop?.name)
            else if (Set::class.java.isAssignableFrom(propertyType))
                facade = BindingAwareSimpleSetProperty<T>(this, prop?.name)
            else if (Map::class.java.isAssignableFrom(propertyType))
                facade = BindingAwareSimpleMapProperty<Any, Any>(this, prop?.name)
            else if (ObservableMap::class.java.isAssignableFrom(propertyType))
                facade = BindingAwareSimpleMapProperty<Any, Any>(this, prop?.name)

            // Match against the type of the Property
            else if (java.lang.Integer::class.java.isAssignableFrom(typeParam))
                facade = BindingAwareSimpleIntegerProperty(this, prop?.name)
            else if (java.lang.Long::class.java.isAssignableFrom(typeParam))
                facade = BindingAwareSimpleLongProperty(this, prop?.name)
            else if (java.lang.Double::class.java.isAssignableFrom(typeParam))
                facade = BindingAwareSimpleDoubleProperty(this, prop?.name)
            else if (java.lang.Float::class.java.isAssignableFrom(typeParam))
                facade = BindingAwareSimpleFloatProperty(this, prop?.name)
            else if (java.lang.Boolean::class.java.isAssignableFrom(typeParam))
                facade = BindingAwareSimpleBooleanProperty(this, prop?.name)
            else if (java.lang.String::class.java.isAssignableFrom(typeParam))
                facade = BindingAwareSimpleStringProperty(this, prop?.name)
            else if (ObservableList::class.java.isAssignableFrom(typeParam))
                facade = BindingAwareSimpleListProperty<T>(this, prop?.name)
            else if (List::class.java.isAssignableFrom(typeParam))
                facade = BindingAwareSimpleListProperty<T>(this, prop?.name)
            else if (ObservableSet::class.java.isAssignableFrom(typeParam))
                facade = BindingAwareSimpleSetProperty<T>(this, prop?.name)
            else if (Set::class.java.isAssignableFrom(typeParam))
                facade = BindingAwareSimpleSetProperty<T>(this, prop?.name)
            else if (Map::class.java.isAssignableFrom(typeParam))
                facade = BindingAwareSimpleMapProperty<Any, Any>(this, prop?.name)

            // Default to Object wrapper
            else
                facade = BindingAwareSimpleObjectProperty<T>(this, prop?.name)
        }

        assignValue(facade, prop)

        facade.addListener(dirtyListener)
        propertyMap[facade] = propertyProducer
        propertyCache[facade] = prop

        // Listener that can track external changes for this facade
        externalChangeListeners[facade] = ChangeListener<Any> { _, _, nv ->
            val facadeProperty = (facade as Property<Any>)
            if (!facadeProperty.isBound)
                facadeProperty.value = nv
        }

        // Update facade when the property returned to us is changed externally
        prop?.addListener(externalChangeListeners[facade]!!)

        // Autocommit makes sure changes are written back to the underlying property. Validation will run before the commit is performed.
        if (autocommit) autocommitProperties.add(facade)

        return facade as ResultType
    }

    inline fun <reified T : Any> property(autocommit: Boolean = false, forceObjectProperty: Boolean = false, noinline op: () -> Property<T>) = PropertyDelegate(bind(autocommit, forceObjectProperty, op))

    val dirtyListener: ChangeListener<Any> = ChangeListener { property, _, newValue ->
        if (ignoreDirtyStateProperties.contains(property!!)) return@ChangeListener

        val sourceValue = propertyMap[property]!!.invoke()?.value
        if (sourceValue == newValue) {
            dirtyProperties.remove(property)
        } else if (!autocommitProperties.contains(property) && !dirtyProperties.contains(property)) {
            dirtyProperties.add(property)
        }
    }

    val isDirty: Boolean get() = dirty.value
    val isNotDirty: Boolean get() = !isDirty

    fun validate(focusFirstError: Boolean = true, decorateErrors: Boolean = true, vararg fields: ObservableValue<*>): Boolean =
            validationContext.validate(focusFirstError, decorateErrors, *fields)

    fun clearDecorators() = validationContext.validate(focusFirstError = false, decorateErrors = false)

    /**
     * This function is called after a successful commit, right before the optional successFn call sent to the commit
     * call is invoked.
     */
    open fun onCommit() {

    }

    /**
     * This function is called after a successful commit, right before the optional successFn call sent to the commit
     * call is invoked.
     *
     * @param commits A list of the committed properties, including the old and new value
     */
    open fun onCommit(commits: List<Commit>) {

    }

    fun commit(vararg fields: ObservableValue<*>, successFn: (() -> Unit)? = null) =
            commit(false, true, fields = *fields, successFn = successFn)

    /**
     * Perform validation and flush the values into the source object if validation passes.
     *
     * Optionally commit only the passed in properties instead of all (default).
     *
     * @param force Force flush even if validation fails
     */
    fun commit(force: Boolean = false, focusFirstError: Boolean = true, vararg fields: ObservableValue<*>, successFn: (() -> Unit)? = null): Boolean {
        var committed = true

        val commits = mutableListOf<Commit>()
        runAndWait {
            if (!validate(focusFirstError, fields = *fields) && !force) {
                committed = false
            } else {
                val commitThese = if (fields.isNotEmpty()) fields.toList() else propertyMap.keys
                for (facade in commitThese) {
                    val prop: Property<*>? = propertyMap[facade]?.invoke()
                    if (prop != null) {
                        val event = Commit(facade, prop.value, facade.value)
                        commits.add(event)
                        prop.value = facade.value
                    }
                }
                dirtyProperties.removeAll(commitThese)
            }
        }

        if (committed) {
            onCommit()
            onCommit(commits)
            successFn?.invoke()
        }
        return committed
    }

    fun markDirty(property: ObservableValue<*>) {
        if (propertyMap.containsKey(property))
            dirtyProperties.add(property)
        else
            throw IllegalArgumentException("The property $property is not a facade of this ViewModel ($this)")
    }

    /**
     * Rollback all or the specified fields
     */
    @Suppress("UNCHECKED_CAST")
    fun rollback(vararg fields: Property<*>) {
        runAndWait {
            val rollbackThese = if (fields.isNotEmpty()) fields.toList() else propertyMap.keys

            for (facade in rollbackThese) {
                val prop: Property<*>? = propertyMap[facade]?.invoke()

                // Rebind external change listener in case the source property changed
                val oldProp = propertyCache[facade]
                if (oldProp != prop) {
                    val extListener = externalChangeListeners[facade] as ChangeListener<Any>
                    oldProp?.removeListener(extListener)
                    prop?.removeListener(extListener)
                    prop?.addListener(extListener)
                    propertyCache[facade] = prop
                }
                assignValue(facade, prop)
            }
            dirtyProperties.clear()
        }
    }

    fun assignValue(facade: Property<*>, prop: Property<*>?) {
        facade.value = prop?.value

        // Never allow null collection values
        if (facade.value == null) {
            when (facade) {
                is ListProperty<*> -> facade.value = FXCollections.observableArrayList()
                is SetProperty<*> -> facade.value = FXCollections.observableSet()
                is MapProperty<*, *> -> facade.value = FXCollections.observableHashMap()
                is MutableList<*> -> facade.value = ArrayList<Any>()
                is MutableMap<*, *> -> facade.value = HashMap<Any, Any>()
            }
        }
    }

    inline fun <reified T> addValidator(
            node: Node,
            property: ObservableValue<T>,
            trigger: ValidationTrigger = ValidationTrigger.OnChange(),
            noinline validator: ValidationContext.(T?) -> ValidationMessage?) {

        validationContext.addValidator(node, property, trigger, validator)
        // Force update of valid state
        validationContext.validate(false, false)
    }

    fun setDecorationProvider(decorationProvider: (ValidationMessage) -> Decorator?) {
        validationContext.decorationProvider = decorationProvider
    }

    val isValid: Boolean get() = validationContext.isValid
    val valid: ReadOnlyBooleanProperty get() = validationContext.valid

    /**
     * Create a boolean binding indicating if the given list of properties are currently valid
     * with regards to the ValidationContext of this ViewModel.
     */
    fun valid(vararg fields: Property<*>): BooleanExpression {
        val matchingValidators = FXCollections.observableArrayList<ValidationContext.Validator<*>>()

        fun updateMatchingValidators() {
            matchingValidators.setAll(validationContext.validators.filter {
                val facade = it.property.viewModelFacade
                facade != null && fields.contains(facade)
            })
        }

        validationContext.validators.onChange { updateMatchingValidators() }
        updateMatchingValidators()

        return booleanListBinding(matchingValidators) { valid }
    }

    /**
     * Extract the value of the corresponding source property
     */
    fun <T> backingValue(property: Property<T>) = propertyMap[property]?.invoke()?.value

    fun <T> isDirty(property: Property<T>) = backingValue(property) != property.value
    fun <T> isNotDirty(property: Property<T>) = !isDirty(property)
}

/**
 * Check if a given property from the ViewModel is dirty. This is a shorthand form of:
 *
 * `model.isDirty(model.property)`
 *
 * With this you can write:
 *
 * `model.property.isDirty`
 *
 */
val <T> Property<T>.isDirty: Boolean get() = (bean as? ViewModel)?.isDirty(this) ?: false
val <T> Property<T>.isNotDirty: Boolean get() = !isDirty

/**
 * Listen to changes in the given observable and call the op with the new value on change.
 * After each change the viewmodel is rolled back to reflect the values in the new source object or objects.
 */
fun <V : ViewModel, T> V.rebindOnChange(observable: ObservableValue<T>, op: (V.(T?) -> Unit)? = null) {
    observable.addListener { _, _, newValue ->
        op?.invoke(this, newValue)
        rollback()
    }
}

/**
 * Rebind the itemProperty of the ViewModel when the itemProperty in the ListCellFragment changes.
 */
fun <V : ItemViewModel<T>, T> V.bindTo(cellFragment: ListCellFragment<T>): V {
    itemProperty.bind(cellFragment.itemProperty)
    return this
}

/**
 * Rebind the itemProperty of the ViewModel when the itemProperty in the TableCellFragment changes.
 */
fun <V : ItemViewModel<T>, S, T> V.bindToItem(cellFragment: TableCellFragment<S, T>): V {
    itemProperty.bind(cellFragment.itemProperty)
    return this
}

/**
 * Rebind the rowItemProperty of the ViewModel when the itemProperty in the TableCellFragment changes.
 */
fun <V : ItemViewModel<S>, S, T> V.bindToRowItem(cellFragment: TableCellFragment<S, T>): V {
    itemProperty.bind(cellFragment.rowItemProperty)
    return this
}

fun <V : ViewModel, T : ObservableValue<X>, X> V.dirtyStateFor(modelField: KProperty1<V, T>): BooleanBinding {
    val prop = modelField.get(this)
    return Bindings.createBooleanBinding(Callable { dirtyProperties.contains(prop) }, dirtyProperties)
}

fun <V : ViewModel, T> V.rebindOnTreeItemChange(observable: ObservableValue<TreeItem<T>>, op: V.(T?) -> Unit) {
    observable.addListener { _, _, newValue ->
        op(newValue?.value)
        rollback()
    }
}

fun <V : ViewModel, T> V.rebindOnChange(tableview: TableView<T>, op: V.(T?) -> Unit)
        = rebindOnChange(tableview.selectionModel.selectedItemProperty(), op)

fun <V : ViewModel, T> V.rebindOnChange(listview: ListView<T>, op: V.(T?) -> Unit)
        = rebindOnChange(listview.selectionModel.selectedItemProperty(), op)

fun <V : ViewModel, T> V.rebindOnChange(treeview: TreeView<T>, op: V.(T?) -> Unit)
        = rebindOnTreeItemChange(treeview.selectionModel.selectedItemProperty(), op)

fun <V : ViewModel, T> V.rebindOnChange(treetableview: TreeTableView<T>, op: V.(T?) -> Unit)
        = rebindOnTreeItemChange(treetableview.selectionModel.selectedItemProperty(), op)

fun <T : ViewModel> T.rebind(op: (T.() -> Unit)) {
    op()
    rollback()
}

/**
 * Add the given validator to a property that resides inside a ViewModel. The supplied node will be
 * decorated by the current decorationProvider for this context inside the ViewModel of the property
 * if validation fails.
 *
 * The validator function is executed in the scope of this ValidationContext to give
 * access to other fields and shortcuts like the error and warning functions.
 *
 * The validation trigger decides when the validation is applied. ValidationTrigger.OnBlur
 * tracks focus on the supplied node while OnChange tracks changes to the property itself.
 */
inline fun <reified T> Property<T>.addValidator(node: Node, trigger: ValidationTrigger = ValidationTrigger.OnChange(), noinline validator: ValidationContext.(T?) -> ValidationMessage?)
        = (bean as? ViewModel)?.addValidator(node, this, trigger, validator)
        ?: throw IllegalArgumentException("The addValidator extension on Property can only be used on properties inside a ViewModel. Use validator.addValidator() instead.")

fun TextInputControl.required(trigger: ValidationTrigger = ValidationTrigger.OnChange(), message: String? = viewModelBundle["required"])
        = validator(trigger) { if (it.isNullOrBlank()) error(message) else null }

inline fun <reified T> ComboBoxBase<T>.required(trigger: ValidationTrigger = ValidationTrigger.OnChange(), message: String? = viewModelBundle["required"])
        = validator(trigger) { if (it == null) error(message) else null }

/**
 * Add a validator to a ComboBox that is already bound to a model property.
 */
inline fun <reified T> ComboBoxBase<T>.validator(trigger: ValidationTrigger = ValidationTrigger.OnChange(), noinline validator: ValidationContext.(T?) -> ValidationMessage?)
        = validator(this, valueProperty(), trigger, validator)

/**
 * Add a validator to a ChoiceBox that is already bound to a model property.
 */
inline fun <reified T> ChoiceBox<T>.validator(trigger: ValidationTrigger = ValidationTrigger.OnChange(), noinline validator: ValidationContext.(T?) -> ValidationMessage?)
        = validator(this, valueProperty(), trigger, validator)

/**
 * Add a validator to a Spinner that is already bound to a model property.
 */
inline fun <reified T> Spinner<T>.validator(trigger: ValidationTrigger = ValidationTrigger.OnChange(), noinline validator: ValidationContext.(T?) -> ValidationMessage?)
        = validator(this, valueFactory.valueProperty(), trigger, validator)

/**
 * Add a validator to a TextInputControl that is already bound to a model property.
 */
fun TextInputControl.validator(trigger: ValidationTrigger = ValidationTrigger.OnChange(), validator: ValidationContext.(String?) -> ValidationMessage?)
        = validator(this, textProperty(), trigger, validator)

/**
 * Add a validator to a Labeled Control that is already bound to a model property.
 */
fun Labeled.validator(trigger: ValidationTrigger = ValidationTrigger.OnChange(), validator: ValidationContext.(String?) -> ValidationMessage?)
        = validator(this, textProperty(), trigger, validator)

/**
 * Add a validator to a ColorPicker that is already bound to a model property.
 */
fun ColorPicker.validator(trigger: ValidationTrigger = ValidationTrigger.OnChange(), validator: ValidationContext.(Paint?) -> ValidationMessage?)
        = validator(this, valueProperty(), trigger, validator)

/**
 * Add a validator to a DatePicker that is already bound to a model property.
 */
fun DatePicker.validator(trigger: ValidationTrigger = ValidationTrigger.OnChange(), validator: ValidationContext.(LocalDate?) -> ValidationMessage?)
        = validator(this, valueProperty(), trigger, validator)

/**
 * Add a validator to a CheckBox that is already bound to a model property.
 */
fun CheckBox.validator(trigger: ValidationTrigger = ValidationTrigger.OnChange(), validator: ValidationContext.(Boolean?) -> ValidationMessage?)
        = validator(this, selectedProperty(), trigger, validator)

/**
 * Add a validator to a RadioButton that is already bound to a model property.
 */
fun RadioButton.validator(trigger: ValidationTrigger = ValidationTrigger.OnChange(), validator: ValidationContext.(Boolean?) -> ValidationMessage?)
        = validator(this, selectedProperty(), trigger, validator)

/**
 * Add a validator to the given Control for the given model property.
 */
inline fun <reified T> validator(control: Control, property: Property<T>, trigger: ValidationTrigger, model: ViewModel? = null, noinline validator: ValidationContext.(T?) -> ValidationMessage?)
        = (model ?: property.viewModel)?.addValidator(control, property, trigger, validator)
        ?: throw IllegalArgumentException("The addValidator extension can only be used on inputs that are already bound bidirectionally to a property in a Viewmodel. Use validator.addValidator() instead or make the property's bean field point to a ViewModel.")

inline fun <reified T> validator(control: Control, property: Property<T>, trigger: ValidationTrigger, noinline validator: ValidationContext.(T?) -> ValidationMessage?)
        = validator(control, property, trigger, null, validator)

/**
 * Extract the ViewModel from a property that is bound towards a ViewModel Facade
 */
@Suppress("UNCHECKED_CAST")
val Property<*>.viewModel: ViewModel? get() = (bean as? ViewModel) ?: ViewModel.getViewModelForProperty(this)

/**
 * Extract the ViewModel Facade from a property that is bound towards it
 */
val ObservableValue<*>.viewModelFacade: Property<*>? get() = ViewModel.getFacadeForProperty(this)

@Suppress("UNCHECKED_CAST")
open class ItemViewModel<T> @JvmOverloads constructor(initialValue: T? = null, val itemProperty: ObjectProperty<T> = SimpleObjectProperty(initialValue)) : ViewModel() {
    var item by itemProperty

    val empty = itemProperty.isNull
    val isEmpty: Boolean get() = empty.value
    val isNotEmpty: Boolean get() = empty.value.not()

    init {
        rebindOnChange(itemProperty)
    }

    fun <N> select(nested: (T) -> ObservableValue<N>) = itemProperty.select(nested)

    fun asyncItem(func: () -> T?) =
            task { func() } success { if (itemProperty.isBound && item is JsonModel) (item as JsonModel).update(it as JsonModel) else item = it }

    @JvmName("bindField")
    inline fun <reified N : Any, ReturnType : Property<N>> bind(property: KProperty1<T, N?>, autocommit: Boolean = false, forceObjectProperty: Boolean = false): ReturnType
            = bind(autocommit, forceObjectProperty) { item?.let { property.get(it).toProperty() } }

    @JvmName("bindMutableField")
    inline fun <reified N : Any, ReturnType : Property<N>> bind(property: KMutableProperty1<T, N>, autocommit: Boolean = false, forceObjectProperty: Boolean = false): ReturnType
            = bind(autocommit, forceObjectProperty) { item?.observable(property) }

    @JvmName("bindProperty")
    inline fun <reified N : Any, reified PropertyType : Property<N>, ReturnType : PropertyType> bind(property: KProperty1<T, PropertyType>, autocommit: Boolean = false, forceObjectProperty: Boolean = false): ReturnType
            = bind(autocommit, forceObjectProperty) { item?.let { property.get(it) } }

    @JvmName("bindMutableProperty")
    inline fun <reified N : Any, reified PropertyType : Property<N>, ReturnType : PropertyType> bind(property: KMutableProperty1<T, PropertyType>, autocommit: Boolean = false, forceObjectProperty: Boolean = false): ReturnType
            = bind(autocommit, forceObjectProperty) { item?.observable(property) } as ReturnType

    @JvmName("bindGetter")
    inline fun <reified N : Any, ReturnType : Property<N>> bind(property: KFunction<N>, autocommit: Boolean = false, forceObjectProperty: Boolean = false): ReturnType
            = bind(autocommit, forceObjectProperty) { item?.let { property.call(it).toProperty() } }

    @JvmName("bindPropertyFunction")
    inline fun <reified N : Any, reified PropertyType : Property<N>, ReturnType : PropertyType> bind(property: KFunction<PropertyType>, autocommit: Boolean = false, forceObjectProperty: Boolean = false): ReturnType
            = bind(autocommit, forceObjectProperty) { item?.let { property.call(it) } }
}

class Commit(val property: ObservableValue<*>, val oldValue: Any?, val newValue: Any?) {
    val changed: Boolean get() = oldValue != newValue
}

/**
 * Mark this ViewModel facade property as dirty in it's owning ViewModel.
 */
fun Property<*>.markDirty() = viewModel?.markDirty(this)
