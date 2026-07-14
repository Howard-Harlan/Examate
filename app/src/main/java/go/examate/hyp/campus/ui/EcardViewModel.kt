package go.examate.hyp.campus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import go.examate.hyp.campus.CampusException
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.campus.model.BankCard
import go.examate.hyp.campus.model.BillRecord
import go.examate.hyp.campus.model.EcardBalance
import go.examate.hyp.campus.model.PayArea
import go.examate.hyp.campus.model.PayBuilding
import go.examate.hyp.campus.model.PayResult
import go.examate.hyp.campus.model.RoomElectricity
import go.examate.hyp.config.PrefsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * EcardScreen 的 ViewModel
 *
 * 将一卡通余额/账单/缴费三个 Tab 的状态从 Composable 中抽出,
 * 统一用 StateFlow 管理,Composable 通过 collectAsStateWithLifecycle() 收集。
 *
 * 优势:
 * 1. 状态与 UI 解耦,便于测试
 * 2. viewModelScope 自动绑定生命周期,屏幕旋转时不丢失
 * 3. 避免 Composable 重建时重复加载
 */
class EcardViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CampusRepository.getInstance()
    private val prefs = PrefsManager.getInstance(app)

    private val _uiState = MutableStateFlow(EcardUiState())
    val uiState: StateFlow<EcardUiState> = _uiState.asStateFlow()

    // ════════════════════════════════════════
    //  余额查询 + 充值
    // ════════════════════════════════════════

    fun loadBalance() {
        val s = _uiState.value.balance
        if (s.loaded || s.loading || s.error.isNotEmpty()) return
        val forceRefresh = s.forceRefresh
        _uiState.update { it.copy(balance = it.balance.copy(loading = true)) }
        viewModelScope.launch {
            try {
                val data = repo.getBalance(prefs, forceRefresh = forceRefresh)
                _uiState.update {
                    it.copy(balance = it.balance.copy(
                        balance = data, loading = false, loaded = true, forceRefresh = false
                    ))
                }
            } catch (e: CampusException) {
                _uiState.update {
                    it.copy(balance = it.balance.copy(
                        loading = false, loaded = true,
                        error = e.message ?: "查询失败", forceRefresh = false
                    ))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(balance = it.balance.copy(
                        loading = false, loaded = true,
                        error = "网络异常: ${e.message ?: "未知错误"}", forceRefresh = false
                    ))
                }
            }
        }
    }

    /** 出错后点击"重试" */
    fun retryBalance() {
        _uiState.update {
            it.copy(balance = it.balance.copy(error = "", loaded = false, forceRefresh = true))
        }
        loadBalance()
    }

    /** 余额已加载后手动刷新 */
    fun refreshBalance() {
        _uiState.update {
            it.copy(balance = it.balance.copy(loaded = false, forceRefresh = true, error = ""))
        }
        loadBalance()
    }

    fun setTopAmount(v: String) {
        _uiState.update { it.copy(balance = it.balance.copy(amount = v)) }
    }

    fun setTopQueryPwd(v: String) {
        _uiState.update { it.copy(balance = it.balance.copy(queryPwd = v)) }
    }

    fun dismissTopping() {
        _uiState.update { it.copy(balance = it.balance.copy(topping = false)) }
    }

    fun dismissCardPicker() {
        _uiState.update { it.copy(balance = it.balance.copy(showCardPicker = false)) }
    }

    /** 加载充值签约银行卡(对齐网页端"先选卡再充值"流程) */
    fun loadTopupCards() {
        val s = _uiState.value.balance
        if (s.loadingCards) return
        _uiState.update {
            it.copy(balance = it.balance.copy(
                loadingCards = true, topError = "", topResult = null,
                selectedCard = null, topupCards = emptyList()
            ))
        }
        viewModelScope.launch {
            try {
                val cards = repo.getTopupBankCards(prefs)
                _uiState.update { st ->
                    val newState = when {
                        cards.isEmpty() -> st.balance.copy(
                            topupCards = cards, loadingCards = false,
                            topError = "没有可用的签约银行卡,请先在一卡通网页端签约银行卡"
                        )
                        cards.size == 1 -> st.balance.copy(
                            topupCards = cards, loadingCards = false,
                            selectedCard = cards[0], topping = true
                        )
                        else -> st.balance.copy(
                            topupCards = cards, loadingCards = false,
                            showCardPicker = true
                        )
                    }
                    st.copy(balance = newState)
                }
            } catch (e: CampusException) {
                _uiState.update {
                    it.copy(balance = it.balance.copy(
                        loadingCards = false, topError = e.message ?: "获取银行卡失败"
                    ))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(balance = it.balance.copy(
                        loadingCards = false, topError = "网络异常: ${e.message ?: "未知错误"}"
                    ))
                }
            }
        }
    }

    /** 多张卡时,从弹窗中选中一张 */
    fun selectTopupCard(card: BankCard) {
        _uiState.update {
            it.copy(balance = it.balance.copy(
                selectedCard = card, showCardPicker = false, topping = true
            ))
        }
    }

    /** 提交充值 */
    fun submitTopUp() {
        val s = _uiState.value.balance
        val amt = s.amount.toDoubleOrNull()
        if (amt == null || amt <= 0) {
            _uiState.update {
                it.copy(balance = it.balance.copy(topError = "请输入有效金额", topResult = null))
            }
            return
        }
        if (s.queryPwd.isBlank()) {
            _uiState.update {
                it.copy(balance = it.balance.copy(topError = "请输入密码", topResult = null))
            }
            return
        }
        val card = s.selectedCard
        if (card == null || card.cardNumberFull.isEmpty()) {
            _uiState.update {
                it.copy(balance = it.balance.copy(topError = "请选择签约银行卡", topResult = null))
            }
            return
        }
        val queryPwd = s.queryPwd
        _uiState.update {
            it.copy(balance = it.balance.copy(topping = false, topError = "", topResult = null))
        }
        viewModelScope.launch {
            try {
                val result = repo.topUp(prefs, amt, queryPwd, card.cardNumberFull)
                _uiState.update { it.copy(balance = it.balance.copy(topResult = result)) }
                // 充值成功后自动刷新余额
                if (result.success) {
                    _uiState.update {
                        it.copy(balance = it.balance.copy(
                            loaded = false, loading = false, forceRefresh = true
                        ))
                    }
                    loadBalance()
                }
            } catch (e: CampusException) {
                _uiState.update {
                    it.copy(balance = it.balance.copy(topError = e.message ?: "充值失败"))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(balance = it.balance.copy(topError = "网络异常: ${e.message ?: "未知错误"}"))
                }
            }
        }
    }

    // ════════════════════════════════════════
    //  账单查询(日期范围 + 类型)
    // ════════════════════════════════════════

    fun setBillStartDate(v: String) {
        _uiState.update { it.copy(bill = it.bill.copy(startDate = v)) }
    }

    fun setBillEndDate(v: String) {
        _uiState.update { it.copy(bill = it.bill.copy(endDate = v)) }
    }

    fun setBillRecordType(v: String) {
        _uiState.update { it.copy(bill = it.bill.copy(recordType = v, showTypePicker = false)) }
    }

    fun showBillTypePicker(show: Boolean) {
        _uiState.update { it.copy(bill = it.bill.copy(showTypePicker = show)) }
    }

    fun queryBills() {
        val s = _uiState.value.bill
        if (s.loading) return
        _uiState.update {
            it.copy(bill = it.bill.copy(loading = true, error = "", queried = true))
        }
        viewModelScope.launch {
            try {
                val data = repo.getBillRecords(prefs, s.startDate, s.endDate, s.recordType)
                _uiState.update { it.copy(bill = it.bill.copy(bills = data, loading = false)) }
            } catch (e: CampusException) {
                _uiState.update {
                    it.copy(bill = it.bill.copy(loading = false, error = e.message ?: "查询失败"))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(bill = it.bill.copy(loading = false, error = "网络异常: ${e.message ?: "未知错误"}"))
                }
            }
        }
    }

    // ════════════════════════════════════════
    //  生活缴费(宿舍电费等,多步选择)
    // ════════════════════════════════════════

    fun setItemNum(v: String) {
        _uiState.update {
            it.copy(payFee = it.payFee.copy(
                itemNum = v, showItemPicker = false,
                area = null, building = null, room = null,
                areas = emptyList(), buildings = emptyList(), rooms = emptyList()
            ))
        }
    }

    fun showItemPicker(show: Boolean) {
        _uiState.update { it.copy(payFee = it.payFee.copy(showItemPicker = show)) }
    }

    /** 点击"校区"行:必要时加载,然后弹出选择 */
    fun onAreaClick() {
        val s = _uiState.value.payFee
        if (s.loadingAreas) return
        if (s.areas.isEmpty()) loadAreas()
        _uiState.update { it.copy(payFee = it.payFee.copy(showAreaPicker = true)) }
    }

    fun loadAreas() {
        val s = _uiState.value.payFee
        if (s.loadingAreas) return
        val itemNum = s.itemNum
        _uiState.update { it.copy(payFee = it.payFee.copy(loadingAreas = true, listError = "")) }
        viewModelScope.launch {
            try {
                val data = repo.getPayAreas(prefs, itemNum)
                _uiState.update {
                    it.copy(payFee = it.payFee.copy(areas = data, loadingAreas = false))
                }
            } catch (e: CampusException) {
                _uiState.update {
                    it.copy(payFee = it.payFee.copy(loadingAreas = false, listError = e.message ?: "查询失败"))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(payFee = it.payFee.copy(
                        loadingAreas = false, listError = "网络异常: ${e.message ?: "未知错误"}"
                    ))
                }
            }
        }
    }

    fun selectArea(id: String) {
        _uiState.update { st ->
            val area = st.payFee.areas.firstOrNull { a -> a.id == id }
            st.copy(payFee = st.payFee.copy(
                area = area, showAreaPicker = false,
                building = null, room = null,
                buildings = emptyList(), rooms = emptyList()
            ))
        }
    }

    fun dismissAreaPicker() {
        _uiState.update { it.copy(payFee = it.payFee.copy(showAreaPicker = false)) }
    }

    /** 点击"楼栋"行:必须有校区,必要时加载,然后弹出选择 */
    fun onBuildingClick() {
        val s = _uiState.value.payFee
        if (s.area == null) return
        if (s.loadingBuildings) return
        if (s.buildings.isEmpty() || s.building == null) loadBuildings()
        _uiState.update { it.copy(payFee = it.payFee.copy(showBuildingPicker = true)) }
    }

    fun loadBuildings() {
        val s = _uiState.value.payFee
        if (s.area == null || s.loadingBuildings) return
        val areaId = s.area!!.id
        val itemNum = s.itemNum
        _uiState.update { it.copy(payFee = it.payFee.copy(loadingBuildings = true, listError = "")) }
        viewModelScope.launch {
            try {
                val data = repo.getBuildings(prefs, areaId, itemNum)
                _uiState.update {
                    it.copy(payFee = it.payFee.copy(buildings = data, loadingBuildings = false))
                }
            } catch (e: CampusException) {
                _uiState.update {
                    it.copy(payFee = it.payFee.copy(loadingBuildings = false, listError = e.message ?: "查询失败"))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(payFee = it.payFee.copy(
                        loadingBuildings = false, listError = "网络异常: ${e.message ?: "未知错误"}"
                    ))
                }
            }
        }
    }

    fun selectBuilding(id: String) {
        _uiState.update { st ->
            val b = st.payFee.buildings.firstOrNull { x -> x.id == id }
            st.copy(payFee = st.payFee.copy(
                building = b, showBuildingPicker = false,
                room = null, rooms = emptyList()
            ))
        }
    }

    fun dismissBuildingPicker() {
        _uiState.update { it.copy(payFee = it.payFee.copy(showBuildingPicker = false)) }
    }

    /** 点击"房间"行:必须有楼栋,必要时加载,然后弹出选择 */
    fun onRoomClick() {
        val s = _uiState.value.payFee
        if (s.building == null) return
        if (s.loadingRooms) return
        if (s.rooms.isEmpty() || s.room == null) loadRooms()
        _uiState.update { it.copy(payFee = it.payFee.copy(showRoomPicker = true)) }
    }

    fun loadRooms() {
        val s = _uiState.value.payFee
        if (s.area == null || s.building == null || s.loadingRooms) return
        val areaId = s.area!!.id
        val buildingId = s.building!!.id
        val itemNum = s.itemNum
        _uiState.update { it.copy(payFee = it.payFee.copy(loadingRooms = true, listError = "")) }
        viewModelScope.launch {
            try {
                val data = repo.getRooms(prefs, areaId, buildingId, itemNum)
                _uiState.update {
                    it.copy(payFee = it.payFee.copy(rooms = data, loadingRooms = false))
                }
            } catch (e: CampusException) {
                _uiState.update {
                    it.copy(payFee = it.payFee.copy(loadingRooms = false, listError = e.message ?: "查询失败"))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(payFee = it.payFee.copy(
                        loadingRooms = false, listError = "网络异常: ${e.message ?: "未知错误"}"
                    ))
                }
            }
        }
    }

    fun selectRoom(id: String) {
        _uiState.update { st ->
            val r = st.payFee.rooms.firstOrNull { x -> x.id == id }
            st.copy(payFee = st.payFee.copy(
                room = r, showRoomPicker = false,
                elecResult = null, elecError = ""
            ))
        }
    }

    fun dismissRoomPicker() {
        _uiState.update { it.copy(payFee = it.payFee.copy(showRoomPicker = false)) }
    }

    fun setPayAmount(v: String) {
        _uiState.update { it.copy(payFee = it.payFee.copy(amount = v)) }
    }

    fun setPayPwd(v: String) {
        _uiState.update { it.copy(payFee = it.payFee.copy(queryPwd = v)) }
    }

    fun queryElectricity() {
        val s = _uiState.value.payFee
        if (s.elecLoading || s.area == null || s.building == null || s.room == null) return
        val areaId = s.area!!.id
        val buildingId = s.building!!.id
        val roomId = s.room!!.id
        val itemNum = s.itemNum
        _uiState.update {
            it.copy(payFee = it.payFee.copy(elecLoading = true, elecError = "", elecResult = null))
        }
        viewModelScope.launch {
            try {
                val data = repo.getRoomElectricity(prefs, areaId, buildingId, roomId, itemNum)
                _uiState.update {
                    it.copy(payFee = it.payFee.copy(elecResult = data, elecLoading = false))
                }
            } catch (e: CampusException) {
                _uiState.update {
                    it.copy(payFee = it.payFee.copy(elecLoading = false, elecError = e.message ?: "查询失败"))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(payFee = it.payFee.copy(
                        elecLoading = false, elecError = "网络异常: ${e.message ?: "未知错误"}"
                    ))
                }
            }
        }
    }

    fun submitPayFee() {
        val s = _uiState.value.payFee
        if (s.submitting) return
        if (s.area == null || s.building == null || s.room == null) {
            _uiState.update {
                it.copy(payFee = it.payFee.copy(submitError = "请先选择校区、楼栋和房间", result = null))
            }
            return
        }
        val amt = s.amount.toDoubleOrNull()
        if (amt == null || amt <= 0) {
            _uiState.update {
                it.copy(payFee = it.payFee.copy(submitError = "请输入有效金额", result = null))
            }
            return
        }
        if (s.queryPwd.isBlank()) {
            _uiState.update {
                it.copy(payFee = it.payFee.copy(submitError = "请输入密码", result = null))
            }
            return
        }
        val areaId = s.area!!.id
        val buildingId = s.building!!.id
        val roomId = s.room!!.id
        val queryPwd = s.queryPwd
        val itemNum = s.itemNum
        _uiState.update {
            it.copy(payFee = it.payFee.copy(submitting = true, submitError = "", result = null))
        }
        viewModelScope.launch {
            try {
                val r = repo.payFee(prefs, areaId, buildingId, roomId, amt, queryPwd, itemNum)
                _uiState.update {
                    it.copy(payFee = it.payFee.copy(result = r, submitting = false))
                }
            } catch (e: CampusException) {
                _uiState.update {
                    it.copy(payFee = it.payFee.copy(submitting = false, submitError = e.message ?: "缴费失败"))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(payFee = it.payFee.copy(
                        submitting = false, submitError = "网络异常: ${e.message ?: "未知错误"}"
                    ))
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════
//  UI State
// ════════════════════════════════════════════════════

data class EcardUiState(
    val balance: BalanceState = BalanceState(),
    val bill: BillState = BillState(),
    val payFee: PayFeeState = PayFeeState()
)

data class BalanceState(
    val loading: Boolean = false,
    val error: String = "",
    val balance: EcardBalance? = null,
    val loaded: Boolean = false,
    val forceRefresh: Boolean = false,
    // 充值表单
    val amount: String = "",
    val queryPwd: String = "",
    val topping: Boolean = false,
    val topResult: PayResult? = null,
    val topError: String = "",
    // 充值:签约银行卡选择(对齐网页端"先选卡再充值"流程)
    val topupCards: List<BankCard> = emptyList(),
    val selectedCard: BankCard? = null,
    val showCardPicker: Boolean = false,
    val loadingCards: Boolean = false
)

data class BillState(
    val startDate: String = LocalDate.now().minusDays(30).toString(),
    val endDate: String = LocalDate.now().toString(),
    val recordType: String = "",
    val showTypePicker: Boolean = false,
    val loading: Boolean = false,
    val error: String = "",
    val bills: List<BillRecord> = emptyList(),
    val queried: Boolean = false
)

data class PayFeeState(
    val itemNum: String = "2",
    val showItemPicker: Boolean = false,
    val area: PayArea? = null,
    val building: PayBuilding? = null,
    val room: PayBuilding? = null,
    val amount: String = "",
    val queryPwd: String = "",
    val loadingAreas: Boolean = false,
    val loadingBuildings: Boolean = false,
    val loadingRooms: Boolean = false,
    val areas: List<PayArea> = emptyList(),
    val buildings: List<PayBuilding> = emptyList(),
    val rooms: List<PayBuilding> = emptyList(),
    val listError: String = "",
    val showAreaPicker: Boolean = false,
    val showBuildingPicker: Boolean = false,
    val showRoomPicker: Boolean = false,
    val submitting: Boolean = false,
    val submitError: String = "",
    val result: PayResult? = null,
    // 房间电费查询
    val elecLoading: Boolean = false,
    val elecError: String = "",
    val elecResult: RoomElectricity? = null
)
