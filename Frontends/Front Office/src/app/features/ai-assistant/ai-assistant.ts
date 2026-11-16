import {
  Component,
  ElementRef,
  PLATFORM_ID,
  inject,
  signal,
  viewChild
} from '@angular/core';
import { isPlatformBrowser, DatePipe } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';
import { environment } from '../../../environments/environment';

type FeedbackVote = 'UP' | 'DOWN';

interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  interactionId?: string;
  toolsUsed?: string[];
  latencyMs?: number;
  model?: string;
  vote?: FeedbackVote;
  feedbackChoice?: FeedbackVote;
  feedbackComment?: string;
  feedbackError?: string;
}

interface AiAskResponse {
  interactionId: string;
  answer: string;
  toolsUsed?: string[];
  latencyMs?: number;
  model?: string;
}

interface LocationPreference {
  roomId?: string;
}

interface FeedbackRequest {
  interactionId: string;
  vote: FeedbackVote;
  comment?: string;
}

interface ApiErrorBody {
  error?: string;
  message?: string;
}

@Component({
  selector: 'app-ai-assistant',
  imports: [FormsModule, DatePipe],
  templateUrl: './ai-assistant.html',
  styleUrl: './ai-assistant.scss'
})
export class AiAssistant {
  private readonly authService = inject(AuthService);
  private readonly http = inject(HttpClient);
  private readonly platformId = inject(PLATFORM_ID);

  private readonly chatContainer = viewChild<ElementRef>('chatContainer');

  protected readonly user = this.authService.user;
  protected readonly userInput = signal('');
  protected readonly isTyping = signal(false);
  protected readonly feedbackBusy = signal<string | null>(null);

  protected readonly currentRoomId = signal<string | null>(
    this.readPreferredRoom()
  );

  protected readonly quickActions = [
    {
      label: 'État de sécurité',
      prompt: 'Quel est l’état de sécurité actuel de mon bâtiment ?'
    },
    {
      label: 'Évacuation',
      prompt: 'Quelles sont les consignes officielles d’évacuation pour ma salle ?'
    },
    {
      label: 'Analyse des risques',
      prompt: 'Quels risques sont signalés dans ma salle selon les données disponibles ?'
    },
    {
      label: 'Conseils de sécurité',
      prompt: 'Quelles précautions de sécurité dois-je prendre ?'
    }
  ];

  protected readonly messages = signal<ChatMessage[]>([
    {
      id: 'welcome',
      role: 'assistant',
      content:
        'Bonjour ! Je suis votre assistant de sécurité du bâtiment. ' +
        'Je peux vous aider à consulter les informations disponibles, ' +
        'les mesures des capteurs et les consignes d’évacuation. ' +
        'Suivez toujours les alarmes et les procédures officielles.',
      timestamp: new Date()
    }
  ]);

  private readPreferredRoom(): string | null {
    if (!isPlatformBrowser(this.platformId)) {
      return null;
    }

    try {
      const raw = localStorage.getItem('smart-building-location-preference');

      if (!raw) {
        return null;
      }

      const preference = JSON.parse(raw) as LocationPreference;

      return typeof preference.roomId === 'string' && preference.roomId.trim()
        ? preference.roomId.trim()
        : null;
    } catch {
      return null;
    }
  }

  async sendMessage(): Promise<void> {
    const question = this.userInput().trim();

    if (!question || this.isTyping()) {
      return;
    }

    const userMessage: ChatMessage = {
      id: 'msg-' + Date.now(),
      role: 'user',
      content: question,
      timestamp: new Date()
    };

    this.messages.update(items => [...items, userMessage]);
    this.userInput.set('');
    this.isTyping.set(true);
    this.scrollToBottom();

    if (!this.authService.getToken()) {
      this.addAssistantMessage(
        'Votre session a expiré ou vous n’êtes pas connecté. Veuillez vous reconnecter.'
      );
      this.isTyping.set(false);
      return;
    }

    try {
      // La salle provient uniquement du profil enregistré.
      const roomId = this.readPreferredRoom();

      this.currentRoomId.set(roomId);

      const payload: { question: string; roomId?: string } = {
        question
      };

      if (roomId) {
        payload.roomId = roomId;
      }

      // AuthInterceptor ajoute automatiquement le JWT.
      const response = await firstValueFrom(
        this.http.post<AiAskResponse>(
          `${environment.apiUrl}/ai/ask`,
          payload
        )
      );

      const answer =
        typeof response?.answer === 'string' && response.answer.trim()
          ? response.answer
          : 'Le service IA n’a fourni aucune réponse exploitable.';

      const aiMessage: ChatMessage = {
        id: 'msg-' + Date.now(),
        role: 'assistant',
        content: answer,
        timestamp: new Date(),
        interactionId: response.interactionId,
        toolsUsed: Array.isArray(response.toolsUsed)
          ? response.toolsUsed
          : [],
        latencyMs: response.latencyMs,
        model: response.model
      };

      this.messages.update(items => [...items, aiMessage]);
    } catch (error: unknown) {
      this.addAssistantMessage(this.getErrorMessage(error));
    } finally {
      this.isTyping.set(false);
      this.scrollToBottom();
    }
  }

  sendQuickAction(prompt: string): void {
    this.userInput.set(prompt);
    void this.sendMessage();
  }

  showNegativeFeedback(messageId: string): void {
    this.updateMessage(messageId, {
      feedbackChoice: 'DOWN',
      feedbackError: undefined
    });
  }

  setFeedbackComment(messageId: string, comment: string): void {
    this.updateMessage(messageId, { feedbackComment: comment });
  }

  async submitFeedback(
    message: ChatMessage,
    vote: FeedbackVote
  ): Promise<void> {
    if (!message.interactionId || message.vote || this.feedbackBusy()) {
      return;
    }

    this.feedbackBusy.set(message.id);

    try {
      const payload: FeedbackRequest = {
        interactionId: message.interactionId,
        vote
      };

      const comment = message.feedbackComment?.trim();

      if (vote === 'DOWN' && comment) {
        payload.comment = comment;
      }

      // POST /api/ai/feedback ; reponse attendue : HTTP 204.
      await firstValueFrom(
        this.http.post<void>(
          `${environment.apiUrl}/ai/feedback`,
          payload
        )
      );

      this.updateMessage(message.id, {
        vote,
        feedbackChoice: undefined,
        feedbackError: undefined
      });
    } catch (error: unknown) {
      this.updateMessage(message.id, {
        feedbackError: this.getErrorMessage(error)
      });
    } finally {
      this.feedbackBusy.set(null);
    }
  }

  private updateMessage(
    id: string,
    changes: Partial<ChatMessage>
  ): void {
    this.messages.update(items =>
      items.map(message =>
        message.id === id ? { ...message, ...changes } : message
      )
    );
  }

  private addAssistantMessage(content: string): void {
    this.messages.update(items => [
      ...items,
      {
        id: 'msg-' + Date.now(),
        role: 'assistant',
        content,
        timestamp: new Date()
      }
    ]);
  }

  private getErrorMessage(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      if (error.status === 0) {
        return 'Impossible de joindre le service IA. Vérifiez que le backend est démarré et que la connexion HTTPS/CORS est autorisée.';
      }

      if (error.status === 401) {
        return 'Votre session a expiré. Veuillez vous reconnecter.';
      }

      const body = error.error as ApiErrorBody | null;

      if (typeof body?.error === 'string' && body.error.trim()) {
        return `Service IA indisponible : ${body.error}`;
      }

      if (typeof body?.message === 'string' && body.message.trim()) {
        return `Service IA indisponible : ${body.message}`;
      }

      return `Le service IA a renvoyé une erreur HTTP ${error.status}.`;
    }

    return 'Une erreur inattendue est survenue lors de la communication avec le service IA.';
  }

  private scrollToBottom(): void {
    setTimeout(() => {
      const element = this.chatContainer()?.nativeElement;

      if (element) {
        element.scrollTop = element.scrollHeight;
      }
    }, 50);
  }
}