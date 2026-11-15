import{a as E,b as R,e as N,h as B}from"./chunk-TH57JZ3E.js";import{b as F,p as I}from"./chunk-MVC6CYG7.js";import{Gb as D,Ha as C,Sa as u,T as b,Ta as p,Va as g,Wa as f,Xa as h,Y as _,Ya as a,Z as v,Za as o,_a as A,cb as x,gb as m,ib as M,ja as d,mb as k,nb as w,qb as P,rb as s,sb as O,tb as y,xa as r,xb as S,yb as T}from"./chunk-CX5RK4KP.js";var H=["chatContainer"],z=(n,t)=>t.label,L=(n,t)=>t.id;function W(n,t){if(n&1){let e=x();a(0,"button",12),m("click",function(){let l=_(e).$implicit,c=M();return v(c.sendQuickAction(l.prompt))}),s(1),o()}if(n&2){let e=t.$implicit;r(),y(" ",e.label," ")}}function G(n,t){n&1&&s(0," \u{1F916} ")}function Q(n,t){n&1&&s(0," \u{1F464} ")}function $(n,t){if(n&1&&(a(0,"div",13)(1,"div",14),u(2,G,1,0)(3,Q,1,0),o(),a(4,"div",15)(5,"div",16),s(6),o(),a(7,"span",17),s(8),S(9,"date"),o()()()),n&2){let e=t.$implicit;P("msg-"+e.role),r(2),p(e.role==="assistant"?2:3),r(4),O(e.content),r(2),y(" ",T(9,5,e.timestamp,"HH:mm")," ")}}function V(n,t){n&1&&(a(0,"div",7)(1,"div",14),s(2,"\u{1F916}"),o(),a(3,"div",18),A(4,"span",19)(5,"span",19)(6,"span",19),o()())}var q=class n{authService=b(I);user=this.authService.user;chatContainer=D("chatContainer");userInput=d("");isTyping=d(!1);messages=d([{id:"welcome",role:"assistant",content:`Hello ${this.user()?.firstName??"there"}! \u{1F44B} I'm the SafeBuilding AI Assistant.

I can help you with:
\u2022 \u{1F6A8} Current emergency status and alerts
\u2022 \u{1F5FA}\uFE0F Evacuation routes and safe exits
\u2022 \u{1F4CA} Sensor data and risk assessments
\u2022 \u{1F4A1} Safety recommendations
\u2022 \u2753 Any safety-related questions

How can I assist you today?`,timestamp:new Date}]);quickActions=[{label:"\u{1F6A8} Current alerts",prompt:"What are the current active alerts?"},{label:"\u{1F5FA}\uFE0F Nearest exit",prompt:"What is the nearest safe exit from my location?"},{label:"\u{1F4CA} Risk assessment",prompt:"Give me a risk assessment for Building A"},{label:"\u{1F4A1} Safety tips",prompt:"What safety precautions should I take right now?"}];sendMessage(){let t=this.userInput().trim();if(!t)return;let e={id:"msg-"+Date.now(),role:"user",content:t,timestamp:new Date};this.messages.update(i=>[...i,e]),this.userInput.set(""),this.isTyping.set(!0),this.scrollToBottom(),setTimeout(()=>{let i=this.generateMockResponse(t),l={id:"msg-"+Date.now(),role:"assistant",content:i,timestamp:new Date};this.messages.update(c=>[...c,l]),this.isTyping.set(!1),this.scrollToBottom()},1200+Math.random()*800)}sendQuickAction(t){this.userInput.set(t),this.sendMessage()}scrollToBottom(){setTimeout(()=>{let t=this.chatContainer()?.nativeElement;t&&(t.scrollTop=t.scrollHeight)},50)}generateMockResponse(t){let e=t.toLowerCase();return e.includes("alert")||e.includes("emergency")||e.includes("danger")?`\u{1F6A8} **Current Active Alerts:**

1. **CRITICAL \u2014 Fire Detected** in Building A, Floor 3, Room 302 (Server Room)
   - Triggered 5 minutes ago
   - Fire suppression system active

2. **HIGH \u2014 Gas Leak Warning** in Building A, Floor 1, Utility Room
   - Elevated methane levels
   - Ventilation activated

\u26A1 **Recommendation:** Evacuate Floor 3 immediately. Use Stairway B. Do NOT use elevators.`:e.includes("exit")||e.includes("evacuat")||e.includes("route")?`\u{1F5FA}\uFE0F **Evacuation Routes from Your Location (Floor 2):**

\u2705 **Stairway A (West)** \u2014 Clear, ~30 seconds walk
\u2705 **Stairway B (East)** \u2014 Clear, ~45 seconds walk
\u26D4 **Elevator** \u2014 Do NOT use during emergency

\u{1F4CD} **Assembly Point:** Parking Lot C (North entrance)

\u{1F4A1} Stay low if smoke is present. Follow illuminated exit signs.`:e.includes("risk")||e.includes("assessment")||e.includes("analysis")?`\u{1F4CA} **AI Risk Assessment \u2014 Building A:**

| Area | Risk Level | Details |
|------|-----------|----------|
| Floor 3 | \u{1F534} CRITICAL | Active fire in Server Room |
| Floor 1 | \u{1F7E1} HIGH | Gas leak in Utility Room |
| Floor 2 | \u{1F7E2} LOW | No immediate threats |

\u{1F916} **AI Analysis:** The fire on Floor 3 is contained to Room 302. Gas ventilation on Floor 1 is operational. Probability of escalation: 15%. Fire response team ETA: 3 minutes.

\u26A0\uFE0F *Note: AI assessments complement but never replace deterministic emergency alarm rules.*`:e.includes("safety")||e.includes("tip")||e.includes("precaution")||e.includes("recommend")?`\u{1F4A1} **Safety Recommendations:**

1. \u{1F6B6} **Stay calm** and follow building evacuation procedures
2. \u{1FA9C} **Use stairs only** \u2014 elevators are disabled during emergencies
3. \u{1F9E3} **Stay low** if you encounter smoke
4. \u{1F6AA} **Close doors** behind you to slow fire spread
5. \u{1F4F1} **Keep your phone charged** for emergency notifications
6. \u{1F465} **Help others** if safe to do so, especially those with mobility issues
7. \u{1F4CD} **Report to assembly point** and confirm your safety

\u{1F916} Based on current conditions, the safest path is through Stairway A on the west side.`:e.includes("sensor")||e.includes("temperature")||e.includes("data")?`\u{1F4E1} **Sensor Readings \u2014 Building A:**

\u{1F321}\uFE0F **Temperature:**
- Floor 1: 22\xB0C (normal)
- Floor 2: 23\xB0C (normal)
- Floor 3: 47\xB0C \u26A0\uFE0F (elevated \u2014 Room 302)

\u{1F4A8} **Air Quality:**
- Floor 1: Methane detected (above threshold)
- Floor 2: Normal
- Floor 3: Smoke particles detected

\u{1F525} **Smoke Detectors:** Triggered on Floor 3
\u{1F4E2} **Fire Alarm:** Active \u2014 building-wide`:`Thank you for your question! \u{1F916}

Based on the current building status, here's what I can tell you:

- There are **2 active alerts** in Building A
- Your current floor (Floor 2) is **safe** but be prepared
- Emergency services have been notified

Would you like me to provide:
\u2022 Details about current alerts
\u2022 Evacuation routes
\u2022 Risk assessment
\u2022 Safety recommendations

Just ask! I'm here to help keep you safe. \u{1F6E1}\uFE0F`}static \u0275fac=function(e){return new(e||n)};static \u0275cmp=C({type:n,selectors:[["app-ai-assistant"]],viewQuery:function(e,i){e&1&&k(i.chatContainer,H,5),e&2&&w()},decls:20,vars:3,consts:[["chatContainer",""],[1,"ai-page"],[1,"page-header"],[1,"quick-actions"],[1,"quick-action-btn"],[1,"chat-container"],[1,"chat-message",3,"class"],[1,"chat-message","msg-assistant"],[1,"chat-input-area"],["type","text","placeholder","Ask about safety, alerts, routes...",1,"chat-input",3,"ngModelChange","keydown.enter","ngModel"],[1,"send-btn",3,"click","disabled"],[1,"ai-disclaimer"],[1,"quick-action-btn",3,"click"],[1,"chat-message"],[1,"msg-avatar"],[1,"msg-bubble"],[1,"msg-content"],[1,"msg-time"],[1,"msg-bubble","typing-indicator"],[1,"dot"]],template:function(e,i){e&1&&(a(0,"div",1)(1,"div",2)(2,"h1"),s(3,"\u{1F916} AI Safety Assistant"),o(),a(4,"p"),s(5,"Intelligent emergency guidance"),o()(),a(6,"div",3),g(7,W,2,1,"button",4,z),o(),a(9,"div",5,0),g(11,$,10,8,"div",6,L),u(13,V,7,0,"div",7),o(),a(14,"div",8)(15,"input",9),m("ngModelChange",function(c){return i.userInput.set(c)})("keydown.enter",function(){return i.sendMessage()}),o(),a(16,"button",10),m("click",function(){return i.sendMessage()}),s(17," \u27A4 "),o()(),a(18,"p",11),s(19," \u26A0\uFE0F AI provides guidance only. Always follow official emergency procedures and deterministic alarm rules. "),o()()),e&2&&(r(7),f(i.quickActions),r(4),f(i.messages()),r(2),p(i.isTyping()?13:-1),r(2),h("ngModel",i.userInput()),r(),h("disabled",!i.userInput().trim()))},dependencies:[B,E,R,N,F],styles:[".ai-page[_ngcontent-%COMP%]{display:flex;flex-direction:column;height:calc(100vh - 120px);padding:1rem;max-width:900px;margin:0 auto}.page-header[_ngcontent-%COMP%]{flex-shrink:0;margin-bottom:.75rem}.page-header[_ngcontent-%COMP%]   h1[_ngcontent-%COMP%]{font-size:1.5rem;font-weight:700;color:#f1f5f9;margin:0}.page-header[_ngcontent-%COMP%]   p[_ngcontent-%COMP%]{color:#94a3b8;font-size:.875rem;margin:.25rem 0 0}.quick-actions[_ngcontent-%COMP%]{display:flex;gap:.5rem;flex-wrap:wrap;margin-bottom:.75rem;flex-shrink:0}.quick-action-btn[_ngcontent-%COMP%]{background:#1e293b;border:1px solid #334155;color:#cbd5e1;padding:.375rem .75rem;border-radius:9999px;font-size:.75rem;cursor:pointer;transition:all .2s;white-space:nowrap}.quick-action-btn[_ngcontent-%COMP%]:hover{background:#334155;border-color:#3b82f6;color:#f1f5f9}.chat-container[_ngcontent-%COMP%]{flex:1;overflow-y:auto;display:flex;flex-direction:column;gap:.75rem;padding:.5rem 0;min-height:0}.chat-message[_ngcontent-%COMP%]{display:flex;gap:.5rem;max-width:85%}.chat-message.msg-user[_ngcontent-%COMP%]{align-self:flex-end;flex-direction:row-reverse}.chat-message.msg-assistant[_ngcontent-%COMP%]{align-self:flex-start}.msg-avatar[_ngcontent-%COMP%]{width:32px;height:32px;border-radius:50%;background:#1e293b;display:flex;align-items:center;justify-content:center;font-size:1rem;flex-shrink:0;border:1px solid #334155}.msg-bubble[_ngcontent-%COMP%]{background:#1e293b;border:1px solid #334155;border-radius:12px;padding:.75rem 1rem;position:relative}.msg-user[_ngcontent-%COMP%]   .msg-bubble[_ngcontent-%COMP%]{background:#1e3a5f;border-color:#2563eb}.msg-content[_ngcontent-%COMP%]{font-size:.875rem;color:#e2e8f0;line-height:1.6;white-space:pre-wrap;word-wrap:break-word}.msg-time[_ngcontent-%COMP%]{font-size:.625rem;color:#64748b;display:block;margin-top:.375rem}.typing-indicator[_ngcontent-%COMP%]{display:flex;gap:4px;align-items:center;padding:.75rem 1.25rem}.typing-indicator[_ngcontent-%COMP%]   .dot[_ngcontent-%COMP%]{width:8px;height:8px;background:#64748b;border-radius:50%;animation:_ngcontent-%COMP%_typing 1.4s infinite ease-in-out}.typing-indicator[_ngcontent-%COMP%]   .dot[_ngcontent-%COMP%]:nth-child(1){animation-delay:0s}.typing-indicator[_ngcontent-%COMP%]   .dot[_ngcontent-%COMP%]:nth-child(2){animation-delay:.2s}.typing-indicator[_ngcontent-%COMP%]   .dot[_ngcontent-%COMP%]:nth-child(3){animation-delay:.4s}@keyframes _ngcontent-%COMP%_typing{0%,60%,to{transform:translateY(0);opacity:.4}30%{transform:translateY(-6px);opacity:1}}.chat-input-area[_ngcontent-%COMP%]{display:flex;gap:.5rem;flex-shrink:0;padding-top:.75rem}.chat-input[_ngcontent-%COMP%]{flex:1;padding:.75rem 1rem;border-radius:12px;border:1px solid #475569;background:#0f172a;color:#f1f5f9;font-size:.9375rem;transition:border-color .2s}.chat-input[_ngcontent-%COMP%]::placeholder{color:#64748b}.chat-input[_ngcontent-%COMP%]:focus{outline:none;border-color:#3b82f6;box-shadow:0 0 0 3px #3b82f626}.send-btn[_ngcontent-%COMP%]{width:48px;height:48px;border-radius:12px;border:none;background:#3b82f6;color:#fff;font-size:1.25rem;cursor:pointer;transition:background .2s;display:flex;align-items:center;justify-content:center}.send-btn[_ngcontent-%COMP%]:hover:not(:disabled){background:#2563eb}.send-btn[_ngcontent-%COMP%]:disabled{opacity:.4;cursor:not-allowed}.ai-disclaimer[_ngcontent-%COMP%]{font-size:.6875rem;color:#64748b;text-align:center;margin-top:.5rem;flex-shrink:0}"]})};export{q as AiAssistant};
