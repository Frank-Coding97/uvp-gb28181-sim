package com.uvp.sim.ui.model

/** UI 层 SIP 状态机 DTO. 1:1 映射 com.uvp.sim.sip.SipState. */
enum class SipStateDto {
    Disconnected,
    Registering,
    Registered,
    InCall,
    Failed,
}
