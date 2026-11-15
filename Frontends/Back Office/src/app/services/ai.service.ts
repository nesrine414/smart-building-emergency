import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface AiResponse {
  interactionId: string;
  answer: string;
  toolsUsed: string[];
  latencyMs: number;
  inputTokens: number;
  outputTokens: number;
  model: string;
}

export interface AiStats {
  count: number;
  successfulCount: number;
  avgLatencyMs: number;
  maxLatencyMs: number;
  errors: number;
  upVotes: number;
  downVotes: number;
  totalInputTokens: number;
  totalOutputTokens: number;
}

export interface AiInteraction {
  id: string;
  kind: string;
  userId: string;
  role: string;
  input: string;
  answer: string | null;
  toolsUsed: string[];
  model: string;
  latencyMs: number;
  inputTokens: number;
  outputTokens: number;
  error: string | null;
  vote: string | null;
  comment: string | null;
  timestamp: number;
}

@Injectable({ providedIn: 'root' })
export class AiService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/ai`;

  askQuestion(question: string, roomId?: string): Observable<AiResponse> {
    const body: { question: string; roomId?: string } = { question };

    if (roomId) {
      body.roomId = roomId;
    }

    return this.http.post<AiResponse>(`${this.url}/ask`, body);
  }

  explainIncident(incidentId: string): Observable<AiResponse> {
    return this.http.post<AiResponse>(
      `${this.url}/explain-incident`,
      { incidentId }
    );
  }

  submitFeedback(
    interactionId: string,
    vote: 'UP' | 'DOWN',
    comment?: string
  ): Observable<void> {
    const body: {
      interactionId: string;
      vote: string;
      comment?: string;
    } = { interactionId, vote };

    if (comment?.trim()) {
      body.comment = comment.trim();
    }

    return this.http.post<void>(`${this.url}/feedback`, body);
  }

  getStats(): Observable<AiStats> {
    return this.http.get<AiStats>(`${this.url}/stats`);
  }

  getInteractions(): Observable<AiInteraction[]> {
    return this.http.get<AiInteraction[]>(`${this.url}/interactions`);
  }
}
