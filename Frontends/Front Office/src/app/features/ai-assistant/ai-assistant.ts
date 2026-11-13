import { Component, signal, inject, ElementRef, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { AuthService } from '../../core/auth/auth.service';

interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
}

@Component({
  selector: 'app-ai-assistant',
  imports: [FormsModule, DatePipe],
  templateUrl: './ai-assistant.html',
  styleUrl: './ai-assistant.scss'
})
export class AiAssistant {

  private readonly authService = inject(AuthService);
  protected readonly user = this.authService.user;

  private readonly chatContainer = viewChild<ElementRef>('chatContainer');

  protected readonly userInput = signal('');
  protected readonly isTyping = signal(false);
  protected readonly messages = signal<ChatMessage[]>([
    {
      id: 'welcome',
      role: 'assistant',
      content: `Hello ${this.user()?.firstName ?? 'there'}! 👋 I'm the SafeBuilding AI Assistant.\n\nI can help you with:\n• 🚨 Current emergency status and alerts\n• 🗺️ Evacuation routes and safe exits\n• 📊 Sensor data and risk assessments\n• 💡 Safety recommendations\n• ❓ Any safety-related questions\n\nHow can I assist you today?`,
      timestamp: new Date()
    }
  ]);

  protected readonly quickActions = [
    { label: '🚨 Current alerts', prompt: 'What are the current active alerts?' },
    { label: '🗺️ Nearest exit', prompt: 'What is the nearest safe exit from my location?' },
    { label: '📊 Risk assessment', prompt: 'Give me a risk assessment for Building A' },
    { label: '💡 Safety tips', prompt: 'What safety precautions should I take right now?' }
  ];

  sendMessage(): void {
    const text = this.userInput().trim();
    if (!text) return;

    const userMsg: ChatMessage = {
      id: 'msg-' + Date.now(),
      role: 'user',
      content: text,
      timestamp: new Date()
    };

    this.messages.update(msgs => [...msgs, userMsg]);
    this.userInput.set('');
    this.isTyping.set(true);
    this.scrollToBottom();

    // Simulate AI response with contextual mock data
    setTimeout(() => {
      const aiResponse = this.generateMockResponse(text);
      const aiMsg: ChatMessage = {
        id: 'msg-' + Date.now(),
        role: 'assistant',
        content: aiResponse,
        timestamp: new Date()
      };
      this.messages.update(msgs => [...msgs, aiMsg]);
      this.isTyping.set(false);
      this.scrollToBottom();
    }, 1200 + Math.random() * 800);
  }

  sendQuickAction(prompt: string): void {
    this.userInput.set(prompt);
    this.sendMessage();
  }

  private scrollToBottom(): void {
    setTimeout(() => {
      const el = this.chatContainer()?.nativeElement;
      if (el) {
        el.scrollTop = el.scrollHeight;
      }
    }, 50);
  }

  private generateMockResponse(question: string): string {
    const q = question.toLowerCase();

    if (q.includes('alert') || q.includes('emergency') || q.includes('danger')) {
      return `🚨 **Current Active Alerts:**\n\n1. **CRITICAL — Fire Detected** in Building A, Floor 3, Room 302 (Server Room)\n   - Triggered 5 minutes ago\n   - Fire suppression system active\n\n2. **HIGH — Gas Leak Warning** in Building A, Floor 1, Utility Room\n   - Elevated methane levels\n   - Ventilation activated\n\n⚡ **Recommendation:** Evacuate Floor 3 immediately. Use Stairway B. Do NOT use elevators.`;
    }

    if (q.includes('exit') || q.includes('evacuat') || q.includes('route')) {
      return `🗺️ **Evacuation Routes from Your Location (Floor 2):**\n\n✅ **Stairway A (West)** — Clear, ~30 seconds walk\n✅ **Stairway B (East)** — Clear, ~45 seconds walk\n⛔ **Elevator** — Do NOT use during emergency\n\n📍 **Assembly Point:** Parking Lot C (North entrance)\n\n💡 Stay low if smoke is present. Follow illuminated exit signs.`;
    }

    if (q.includes('risk') || q.includes('assessment') || q.includes('analysis')) {
      return `📊 **AI Risk Assessment — Building A:**\n\n| Area | Risk Level | Details |\n|------|-----------|----------|\n| Floor 3 | 🔴 CRITICAL | Active fire in Server Room |\n| Floor 1 | 🟡 HIGH | Gas leak in Utility Room |\n| Floor 2 | 🟢 LOW | No immediate threats |\n\n🤖 **AI Analysis:** The fire on Floor 3 is contained to Room 302. Gas ventilation on Floor 1 is operational. Probability of escalation: 15%. Fire response team ETA: 3 minutes.\n\n⚠️ *Note: AI assessments complement but never replace deterministic emergency alarm rules.*`;
    }

    if (q.includes('safety') || q.includes('tip') || q.includes('precaution') || q.includes('recommend')) {
      return `💡 **Safety Recommendations:**\n\n1. 🚶 **Stay calm** and follow building evacuation procedures\n2. 🪜 **Use stairs only** — elevators are disabled during emergencies\n3. 🧣 **Stay low** if you encounter smoke\n4. 🚪 **Close doors** behind you to slow fire spread\n5. 📱 **Keep your phone charged** for emergency notifications\n6. 👥 **Help others** if safe to do so, especially those with mobility issues\n7. 📍 **Report to assembly point** and confirm your safety\n\n🤖 Based on current conditions, the safest path is through Stairway A on the west side.`;
    }

    if (q.includes('sensor') || q.includes('temperature') || q.includes('data')) {
      return `📡 **Sensor Readings — Building A:**\n\n🌡️ **Temperature:**\n- Floor 1: 22°C (normal)\n- Floor 2: 23°C (normal)\n- Floor 3: 47°C ⚠️ (elevated — Room 302)\n\n💨 **Air Quality:**\n- Floor 1: Methane detected (above threshold)\n- Floor 2: Normal\n- Floor 3: Smoke particles detected\n\n🔥 **Smoke Detectors:** Triggered on Floor 3\n📢 **Fire Alarm:** Active — building-wide`;
    }

    return `Thank you for your question! 🤖\n\nBased on the current building status, here's what I can tell you:\n\n- There are **2 active alerts** in Building A\n- Your current floor (Floor 2) is **safe** but be prepared\n- Emergency services have been notified\n\nWould you like me to provide:\n• Details about current alerts\n• Evacuation routes\n• Risk assessment\n• Safety recommendations\n\nJust ask! I'm here to help keep you safe. 🛡️`;
  }
}
